using System;
using System.Net;
using System.Net.Sockets;
using System.Text;
using System.Threading;
using System.Threading.Tasks;
using Newtonsoft.Json;

namespace NexLink.Services
{
    public class UdpDiscoveryService
    {
        private const int Port = 55555;
        private UdpClient? _udpClient;
        private CancellationTokenSource? _cts;

        public void StartBroadcasting(string deviceId, string deviceName, int localWsPort)
        {
            StopBroadcasting();
            _cts = new CancellationTokenSource();
            _udpClient = new UdpClient();
            _udpClient.EnableBroadcast = true;

            var payload = JsonConvert.SerializeObject(new
            {
                type = "nexlink_discovery",
                deviceId = deviceId,
                deviceName = deviceName,
                port = localWsPort
            });
            var bytes = Encoding.UTF8.GetBytes(payload);
            var endPoint = new IPEndPoint(IPAddress.Broadcast, Port);

            Task.Run(async () =>
            {
                while (_cts != null && !_cts.Token.IsCancellationRequested)
                {
                    try
                    {
                        await _udpClient.SendAsync(bytes, bytes.Length, endPoint);
                        await Task.Delay(2000, _cts.Token);
                    }
                    catch { }
                }
            });
        }

        public void StopBroadcasting()
        {
            _cts?.Cancel();
            _cts = null;
            _udpClient?.Close();
            _udpClient = null;
        }
    }
}
