using System;
using System.Collections.Generic;
using System.Linq;
using Fleck;
using Newtonsoft.Json.Linq;
using Newtonsoft.Json;

namespace NexLink.Services
{
    public class LocalWebSocketServer
    {
        private WebSocketServer? _server;
        private readonly List<IWebSocketConnection> _clients = new();
        public int Port { get; private set; } = 49152; // Default starting port

        public event Action<string>? MessageReceived;
        public event Action? ClientConnected;
        public event Action? ClientDisconnected;

        public void Start(int port = 49152)
        {
            Port = port;
            try
            {
                _server = new WebSocketServer($"ws://0.0.0.0:{Port}");
                _server.Start(socket =>
                {
                    socket.OnOpen = () =>
                    {
                        Console.WriteLine($"[LocalWS] Client connected: {socket.ConnectionInfo.ClientIpAddress}");
                        lock (_clients) { _clients.Add(socket); }
                        ClientConnected?.Invoke();
                    };
                    
                    socket.OnClose = () =>
                    {
                        Console.WriteLine($"[LocalWS] Client disconnected");
                        lock (_clients) { _clients.Remove(socket); }
                        ClientDisconnected?.Invoke();
                    };

                    socket.OnMessage = message =>
                    {
                        MessageReceived?.Invoke(message);
                    };
                });
                Console.WriteLine($"[LocalWS] Server started on port {Port}");
            }
            catch (Exception ex)
            {
                Console.WriteLine($"[LocalWS] Error starting server on port {Port}: {ex.Message}");
                // Try next port if occupied
                Start(port + 1);
            }
        }

        public void Stop()
        {
            foreach (var client in _clients.ToList())
                client.Close();
            _clients.Clear();
            _server?.Dispose();
            _server = null;
        }

        public void Broadcast(object data)
        {
            var json = data is string s ? s : JsonConvert.SerializeObject(data);
            lock (_clients)
            {
                foreach (var client in _clients)
                {
                    try { client.Send(json); } catch { }
                }
            }
        }
    }
}
