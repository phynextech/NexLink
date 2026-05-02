using System;
using System.Collections.Concurrent;
using System.Net;
using System.Net.Sockets;
using System.Threading;
using System.Threading.Tasks;
using Newtonsoft.Json;
using Newtonsoft.Json.Linq;
using SocketIOClient;
using SocketIOClient.Transport;

namespace NexLink.Services
{
    /// <summary>
    /// NexLink WebSocket/Socket.IO Service — cloud-only architecture.
    ///
    /// Replaces the old WebSocketSharp dual-mode (local + relay) with a
    /// single Socket.IO client that always connects to the Render relay server.
    ///
    /// Connection flow:
    ///   1. ConnectToRelay(userId, deviceId, firebaseToken)
    ///   2. On connect: emit "connect_device" { userId, deviceId, role="desktop" }
    ///   3. Server places this socket in room userId:deviceId
    ///   4. All events are forwarded transparently to the Android phone
    ///   5. On disconnect: SocketIOClient auto-reconnects every 5 seconds
    /// </summary>
    public class WebSocketService
    {
        // ─── Relay Server URL ─────────────────────────────────────────────
        public const string RelayServerUrl = "https://nexlink-khhe.onrender.com";

        private SocketIOClient.SocketIO? _socket;
        private string _userId   = "";
        private string _deviceId = "";
        private bool _isConnecting = false;
        private readonly object _lock = new();

        // Sequential send queue — prevents flooding during reconnect bursts
        private readonly ConcurrentQueue<string> _sendQueue = new();
        private CancellationTokenSource _sendCts = new();
        private bool _sendLoopRunning = false;

        // ─── Events ───────────────────────────────────────────────────────
        public event Action<JObject>? MessageReceived;
        public event Action?          PhoneConnected;
        public event Action?          PhoneDisconnected;

        private bool _isPhoneOnline = false;
        public  bool IsPhoneConnected => _isPhoneOnline;
        public  bool IsSocketConnected => _socket?.Connected ?? false;

        // ─── Connect ──────────────────────────────────────────────────────
        /// <summary>
        /// Connect this desktop to the Render relay server as role "desktop".
        /// </summary>
        public void ConnectToRelay(string userId, string deviceId, string firebaseToken = "")
        {
            lock (_lock)
            {
                if (_isConnecting) return;
                _isConnecting = true;
            }

            _userId   = userId;
            _deviceId = deviceId;

            Task.Run(() => BuildAndConnect(firebaseToken));
        }

        private async Task BuildAndConnect(string token)
        {
            _socket?.DisconnectAsync();

            var options = new SocketIOOptions
            {
                Transport           = TransportProtocol.WebSocket,
                ReconnectionDelay   = 5000,
                ReconnectionDelayMax = 5000,
                Reconnection        = true,
                Auth                = new { token, userId = _userId },
            };

            _socket = new SocketIOClient.SocketIO(RelayServerUrl, options);

            // ── Connect ───────────────────────────────────────────────────
            _socket.OnConnected += async (_, _) =>
            {
                Console.WriteLine($"[Socket.IO] Connected as desktop  id={_socket.Id}");
                StartSendLoop();

                // Register in room
                await _socket.EmitAsync("connect_device", new
                {
                    userId     = _userId,
                    deviceId   = _deviceId,
                    role       = "desktop",
                    deviceName = Environment.MachineName,
                });
            };

            // ── Disconnect ────────────────────────────────────────────────
            _socket.OnDisconnected += (_, reason) =>
            {
                Console.WriteLine($"[Socket.IO] Disconnected: {reason}");
                _isPhoneOnline = false;
                PhoneDisconnected?.Invoke();
                _sendCts.Cancel();
            };

            // ── Reconnect ─────────────────────────────────────────────────
            _socket.OnReconnected += (_, attempt) =>
            {
                Console.WriteLine($"[Socket.IO] Reconnected (attempt {attempt})");
                _socket.EmitAsync("connect_device", new
                {
                    userId     = _userId,
                    deviceId   = _deviceId,
                    role       = "desktop",
                    deviceName = Environment.MachineName,
                });
            };

            _socket.OnError += (_, e) =>
            {
                Console.WriteLine($"[Socket.IO] Error: {e}");
            };

            // ── Peer presence ─────────────────────────────────────────────
            _socket.On("peer_online", _ =>
            {
                if (!_isPhoneOnline)
                {
                    _isPhoneOnline = true;
                    PhoneConnected?.Invoke();
                }
            });

            _socket.On("peer_offline", _ =>
            {
                if (_isPhoneOnline)
                {
                    _isPhoneOnline = false;
                    PhoneDisconnected?.Invoke();
                }
            });

            _socket.On("device_registered", data =>
            {
                Console.WriteLine($"[Socket.IO] Registered in room: {data}");
            });

            // ── Register all relayed events ───────────────────────────────
            RegisterRelayedEvents();

            // ── Connect ───────────────────────────────────────────────────
            try
            {
                await _socket.ConnectAsync();
                lock (_lock) { _isConnecting = false; }
            }
            catch (Exception ex)
            {
                Console.WriteLine($"[Socket.IO] Initial connect failed: {ex.Message}");
                lock (_lock) { _isConnecting = false; }
            }
        }

        /// <summary>
        /// Register a handler for each event type the phone might send.
        /// The handler parses the payload and fires MessageReceived.
        /// </summary>
        private void RegisterRelayedEvents()
        {
            if (_socket == null) return;

            var events = new[]
            {
                "handshake", "request_info", "get_wallpaper",
                "volume", "brightness", "lock_pc",
                "media_control", "media_seek",
                "app_list", "launch_app",
                "browse", "open_file", "download_file",
                "clipboard_push", "clipboard_pull", "clipboard_sync",
                "start_screen", "stop_screen",
                "start_camera", "stop_camera",
                "notification", "send_notification",
                "sms_send",
                "webrtc_offer", "webrtc_answer", "webrtc_ice",
                // USB / mouse control
                "mouse_move", "mouse_tap", "mouse_right_tap", "mouse_scroll",
                "usb_connected", "usb_disconnected",
            };

            foreach (var ev in events)
            {
                var eventName = ev; // capture for closure
                _socket.On(eventName, response =>
                {
                    try
                    {
                        JObject? msg = null;
                        
                        // Try getting as JObject directly (standard for Socket.IO object emits)
                        try { msg = response.GetValue<JObject>(0); } catch { }

                        if (msg == null)
                        {
                            // Try getting as string and parsing (fallback)
                            try 
                            { 
                                var raw = response.GetValue<string>(0);
                                if (!string.IsNullOrWhiteSpace(raw))
                                    msg = JObject.Parse(raw);
                            } catch { }
                        }

                        if (msg == null) msg = new JObject();

                        if (!msg.ContainsKey("type"))
                            msg["type"] = eventName;

                        MessageReceived?.Invoke(msg);
                    }
                    catch (Exception ex)
                    {
                        Console.WriteLine($"[Socket.IO] Parse error on '{eventName}': {ex.Message}");
                    }
                });
            }
        }

        // ─── Send API ─────────────────────────────────────────────────────

        /// <summary>Send any object as the matching Socket.IO event (keyed by "type" field).</summary>
        public void Send(object payload)
        {
            var json = JsonConvert.SerializeObject(payload);
            _sendQueue.Enqueue(json);
        }

        public void SendRaw(string json) => _sendQueue.Enqueue(json);

        private void StartSendLoop()
        {
            if (_sendLoopRunning) { _sendCts.Cancel(); }
            _sendCts = new CancellationTokenSource();
            _sendLoopRunning = true;

            Task.Run(async () =>
            {
                var token = _sendCts.Token;
                while (!token.IsCancellationRequested)
                {
                    if (_sendQueue.TryDequeue(out var json))
                    {
                        try
                        {
                            var msg = JObject.Parse(json);
                            var eventType = msg["type"]?.ToString() ?? "message";
                            // Remove "type" from the data payload to keep event args clean
                            msg.Remove("type");

                            if (_socket?.Connected == true)
                                await _socket.EmitAsync(eventType, msg);

                            await Task.Delay(15, token);
                        }
                        catch (OperationCanceledException) { break; }
                        catch (Exception ex)
                        {
                            Console.WriteLine($"[SendLoop] Error: {ex.Message}");
                        }
                    }
                    else
                    {
                        await Task.Delay(10, token);
                    }
                }
                _sendLoopRunning = false;
            }, _sendCts.Token);
        }

        // ─── Stop ─────────────────────────────────────────────────────────
        public async Task StopAsync()
        {
            _sendCts.Cancel();
            if (_socket != null)
                await _socket.DisconnectAsync();
        }

        public void Stop() => StopAsync().GetAwaiter().GetResult();

        // ─── Network helpers (kept for WiFi SSID / system info) ───────────
        public static string GetLocalIPAddress()
        {
            try
            {
                using var socket = new System.Net.Sockets.Socket(
                    System.Net.Sockets.AddressFamily.InterNetwork,
                    System.Net.Sockets.SocketType.Dgram, 0);
                socket.Connect("8.8.8.8", 65530);
                var ep = socket.LocalEndPoint as System.Net.IPEndPoint;
                if (ep != null) return ep.Address.ToString();
            }
            catch { }
            return "127.0.0.1";
        }

        public static string GetWifiSSID()
        {
            try
            {
                var proc = new System.Diagnostics.Process
                {
                    StartInfo = new System.Diagnostics.ProcessStartInfo
                    {
                        FileName               = "netsh",
                        Arguments              = "wlan show interfaces",
                        RedirectStandardOutput = true,
                        UseShellExecute        = false,
                        CreateNoWindow         = true,
                    }
                };
                proc.Start();
                var output = proc.StandardOutput.ReadToEnd();
                proc.WaitForExit();
                foreach (var line in output.Split('\n'))
                {
                    var t = line.Trim();
                    if (t.StartsWith("SSID") && !t.Contains("BSSID"))
                    {
                        var parts = t.Split(':', 2);
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
}
