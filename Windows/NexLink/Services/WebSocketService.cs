using System;
using System.Collections.Concurrent;
using System.Collections.Generic;
using System.Linq;
using System.Net;
using System.Net.Sockets;
using System.Text;
using System.Threading;
using System.Threading.Tasks;
using NexLink.Models;
using Newtonsoft.Json;
using Newtonsoft.Json.Linq;
using WebSocketSharp;
using WebSocketSharp.Server;

namespace NexLink.Services
{
    /// <summary>
    /// Handles both LOCAL (LAN) and RELAY (cross-network) WebSocket connections.
    ///
    /// Modes:
    ///   LOCAL  — Acts as WS server on LAN port 8765 (same WiFi network)
    ///   RELAY  — Connects to cloud relay server as "desktop" client (any network)
    ///
    /// When RELAY is active, all messages are transparently forwarded through
    /// the relay server to the Android phone, regardless of network.
    /// </summary>
    public class WebSocketService
    {
        // ─── Relay Server URL ─────────────────────────────────────────────────
        public const string RelayServerUrl = "wss://nexlink-relay.onrender.com/relay";

        private WebSocketServer? _localServer;
        private LinkBridgeBehavior? _activeBehavior;
        private readonly object _lock = new();
        private Timer? _heartbeatTimer;

        // Relay client (used when connecting through internet)
        private WebSocket? _relayClient;
        private bool _isRelayMode = false;
        private string _pairId = "";
        private CancellationTokenSource _relayCts = new();

        // Sequential send queue prevents pipe overload from concurrent sends
        private readonly ConcurrentQueue<string> _sendQueue = new();
        private Task? _sendTask;
        private CancellationTokenSource _sendCts = new();

        public event Action<JObject>? MessageReceived;
        public event Action? PhoneConnected;
        public event Action? PhoneDisconnected;
        
        private bool _isRelayPeerOnline = false;
        public bool IsPhoneConnected => (_activeBehavior?.State == WebSocketState.Open) || _isRelayPeerOnline;
        
        public bool IsRelayMode => _isRelayMode;
        public string CurrentPairId => _pairId;

        // ─── LOCAL mode ───────────────────────────────────────────────────────
        public void Start(int port)
        {
            _localServer = new WebSocketServer($"ws://0.0.0.0:{port}");
            _localServer.KeepClean = false;
            _localServer.WaitTime = TimeSpan.FromSeconds(60);

            _localServer.AddWebSocketService<LinkBridgeBehavior>("/", b =>
            {
                b.MessageReceived += OnMessage;
                b.Connected += () =>
                {
                    lock (_lock) { _activeBehavior = b; }
                    ResetSendQueue();
                    PhoneConnected?.Invoke();
                    StartHeartbeat();
                };
                b.Disconnected += () =>
                {
                    lock (_lock)
                    {
                        if (_activeBehavior == b) { _activeBehavior = null; }
                    }
                    StopHeartbeat();
                    if (!IsPhoneConnected) PhoneDisconnected?.Invoke();
                };
            });
            _localServer.Start();
        }

        // ─── RELAY mode ───────────────────────────────────────────────────────
        /// <summary>
        /// Connect this desktop to the relay server using a shared pairId.
        /// The Android phone connects with the same pairId as "mobile".
        /// All messages are forwarded transparently.
        /// </summary>
        public void StartRelay(string pairId)
        {
            _isRelayMode = true;
            _pairId = pairId;
            _relayCts = new CancellationTokenSource();

            var url = $"{RelayServerUrl}?pairId={pairId}&role=desktop";
            ConnectRelayClient(url, _relayCts.Token);
        }

        private void ConnectRelayClient(string url, CancellationToken token)
        {
            if (token.IsCancellationRequested) return;

            _relayClient = new WebSocket(url);
            _relayClient.WaitTime = TimeSpan.FromSeconds(60);

            _relayClient.OnOpen += (_, _) =>
            {
                Console.WriteLine($"[Relay] Connected as desktop, pairId={_pairId}");
                ResetSendQueue();
                StartHeartbeat();
            };

            _relayClient.OnMessage += (_, e) =>
            {
                try
                {
                    var json = JObject.Parse(e.Data);
                    var type = json["type"]?.ToString() ?? "";

                    if (type == "ping") { SendRaw("{\"type\":\"pong\"}"); return; }
                    if (type == "pong") return;

                    if (type == "relay_peer_online")
                    {
                        if (!_isRelayPeerOnline)
                        {
                            _isRelayPeerOnline = true;
                            PhoneConnected?.Invoke();
                        }
                        return;
                    }

                    if (type == "relay_peer_offline")
                    {
                        if (_isRelayPeerOnline)
                        {
                            _isRelayPeerOnline = false;
                            StopHeartbeat();
                            if (!IsPhoneConnected) PhoneDisconnected?.Invoke();
                        }
                        return;
                    }

                    OnMessage(json);
                }
                catch { }
            };

            _relayClient.OnClose += (_, e) =>
            {
                Console.WriteLine($"[Relay] Connection closed: {e.Reason}");
                _isRelayPeerOnline = false;
                StopHeartbeat();
                if (!IsPhoneConnected) PhoneDisconnected?.Invoke();

                // Auto-reconnect with exponential backoff
                if (!token.IsCancellationRequested)
                {
                    Task.Delay(5000, token).ContinueWith(_ =>
                    {
                        if (!token.IsCancellationRequested)
                            ConnectRelayClient(url, token);
                    }, token);
                }
            };

            _relayClient.OnError += (_, e) =>
            {
                Console.WriteLine($"[Relay] Error: {e.Message}");
            };

            _relayClient.Connect();
        }

        // ─── Shared internals ─────────────────────────────────────────────────
        private void ResetSendQueue()
        {
            while (_sendQueue.TryDequeue(out _)) { }
            _sendCts.Cancel();
            _sendCts = new CancellationTokenSource();
            StartSendLoop(_sendCts.Token);
        }

        private void StartSendLoop(CancellationToken token)
        {
            _sendTask = Task.Run(async () =>
            {
                while (!token.IsCancellationRequested)
                {
                    if (_sendQueue.TryDequeue(out var msg))
                    {
                        try
                        {
                            // Send via relay client if available
                            if (_relayClient?.ReadyState == WebSocketState.Open)
                            {
                                _relayClient.Send(msg);
                            }
                            
                            // Send via local client if available
                            LinkBridgeBehavior? beh;
                            lock (_lock) { beh = _activeBehavior; }
                            beh?.SendMessage(msg);

                            await Task.Delay(20, token);
                        }
                        catch { }
                    }
                    else
                    {
                        await Task.Delay(10, token);
                    }
                }
            }, token);
        }

        private void StartHeartbeat()
        {
            StopHeartbeat();
            _heartbeatTimer = new Timer(_ =>
            {
                try { Enqueue("{\"type\":\"ping\"}"); }
                catch { }
            }, null, 20000, 20000);
        }

        private void StopHeartbeat()
        {
            _heartbeatTimer?.Dispose();
            _heartbeatTimer = null;
        }

        private void OnMessage(JObject msg) => MessageReceived?.Invoke(msg);
        private void Enqueue(string json) => _sendQueue.Enqueue(json);

        public void Send(object payload)
        {
            var json = JsonConvert.SerializeObject(payload);
            Enqueue(json);
        }

        public void SendRaw(string json) => Enqueue(json);

        public void Stop()
        {
            StopHeartbeat();
            _sendCts.Cancel();
            _relayCts.Cancel();
            _relayClient?.Close();
            _localServer?.Stop();
        }

        // ─── Network helpers ──────────────────────────────────────────────────
        public static string GetLocalIPAddress()
        {
            try
            {
                using var socket = new Socket(AddressFamily.InterNetwork, SocketType.Dgram, 0);
                socket.Connect("8.8.8.8", 65530);
                var endPoint = socket.LocalEndPoint as IPEndPoint;
                if (endPoint != null) return endPoint.Address.ToString();
            }
            catch { }

            return GetAllLocalIPs().FirstOrDefault(ip => !ip.StartsWith("169.") && ip != "127.0.0.1") ?? "127.0.0.1";
        }

        public static List<string> GetAllLocalIPs()
        {
            var ips = new List<string>();
            try
            {
                var interfaces = System.Net.NetworkInformation.NetworkInterface.GetAllNetworkInterfaces();
                foreach (var ni in interfaces)
                {
                    if (ni.OperationalStatus != System.Net.NetworkInformation.OperationalStatus.Up) continue;
                    if (ni.NetworkInterfaceType == System.Net.NetworkInformation.NetworkInterfaceType.Loopback) continue;
                    var name = ni.Name.ToLower();
                    if (name.Contains("virtual") || name.Contains("vmware") || name.Contains("loopback") ||
                        name.Contains("pseudo") || name.Contains("tunnel") || name.Contains("vethernet")) continue;

                    foreach (var ua in ni.GetIPProperties().UnicastAddresses)
                    {
                        if (ua.Address.AddressFamily == AddressFamily.InterNetwork)
                        {
                            var ip = ua.Address.ToString();
                            if (ip != "127.0.0.1" && !ip.StartsWith("169."))
                                ips.Add(ip);
                        }
                    }
                }
            }
            catch { }
            return ips;
        }

        public static string GetWifiSSID()
        {
            try
            {
                var proc = new System.Diagnostics.Process
                {
                    StartInfo = new System.Diagnostics.ProcessStartInfo
                    {
                        FileName = "netsh",
                        Arguments = "wlan show interfaces",
                        RedirectStandardOutput = true,
                        UseShellExecute = false,
                        CreateNoWindow = true
                    }
                };
                proc.Start();
                var output = proc.StandardOutput.ReadToEnd();
                proc.WaitForExit();

                foreach (var line in output.Split('\n'))
                {
                    var trimmed = line.Trim();
                    if (trimmed.StartsWith("SSID") && !trimmed.Contains("BSSID"))
                    {
                        var parts = trimmed.Split(':', 2);
                        if (parts.Length >= 2)
                        {
                            var ssid = parts[1].Trim();
                            if (!string.IsNullOrEmpty(ssid)) return ssid;
                        }
                    }
                }
            }
            catch { }
            return "Unknown";
        }
    }

    // ─── Local WebSocket behavior (LAN mode only) ──────────────────────────
    public class LinkBridgeBehavior : WebSocketBehavior
    {
        public event Action<JObject>? MessageReceived;
        public event Action? Connected;
        public event Action? Disconnected;

        protected override void OnOpen() => Connected?.Invoke();

        protected override void OnMessage(MessageEventArgs e)
        {
            try
            {
                var json = JObject.Parse(e.Data);
                if (json["type"]?.ToString() == "pong") return;
                MessageReceived?.Invoke(json);
            }
            catch { }
        }

        protected override void OnClose(CloseEventArgs e) => Disconnected?.Invoke();
        protected override void OnError(WebSocketSharp.ErrorEventArgs e) => Disconnected?.Invoke();

        public void SendMessage(string json)
        {
            if (State == WebSocketState.Open)
            {
                try { Send(json); }
                catch { }
            }
        }
    }
}
