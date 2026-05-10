using System;
using System.Threading.Tasks;
using Newtonsoft.Json.Linq;
using SIPSorcery.Net;
using SIPSorceryMedia.Windows;
using SIPSorcery.Media;
using SIPSorceryMedia.Abstractions;
using SIPSorceryMedia.Encoders;
using System.Linq;
using System.Drawing;
using System.Drawing.Imaging;
using System.IO;
using System.Runtime.InteropServices;
using System.Windows.Forms;
using SharpDX;
using SharpDX.Direct3D11;
using SharpDX.DXGI;
using Device = SharpDX.Direct3D11.Device;
using MapFlags = SharpDX.Direct3D11.MapFlags;

namespace NexLink.Services
{
    public class WebRtcManager
    {
        private RTCPeerConnection? _peerConnection;
        private readonly Action<object> _sendSignalingMessage;
        private WindowsVideoEndPoint? _camSource;
        private SIPSorceryMedia.Encoders.VideoEncoderEndPoint? _screenSource;
        private MediaStreamTrack? _videoTrack;
        private bool _isStreaming = false;

        public WebRtcManager(Action<object> sendSignalingMessage)
        {
            _sendSignalingMessage = sendSignalingMessage;
        }

        public async Task InitializeAsync()
        {
            if (_peerConnection != null) return;

            var config = new RTCConfiguration
            {
                iceServers = new System.Collections.Generic.List<RTCIceServer>
                {
                    new RTCIceServer { urls = "stun:stun.l.google.com:19302" }
                }
            };
            _peerConnection = new RTCPeerConnection(config);

            _peerConnection.onconnectionstatechange += state =>
            {
                Console.WriteLine($"[WebRTC] Peer connection state changed to {state}");
                if (state == RTCPeerConnectionState.closed || state == RTCPeerConnectionState.failed)
                {
                    _isStreaming = false;
                }
            };

            _peerConnection.onicecandidate += candidate =>
            {
                _sendSignalingMessage(new
                {
                    type = "webrtc_ice",
                    candidate = candidate.toJSON()
                });
            };

            // Unified Plan for better compatibility
            var dataChannel = await _peerConnection.createDataChannel("nexlink_data");
            dataChannel.onopen += () => Console.WriteLine("[WebRTC] Data channel opened");

            _peerConnection.OnReceiveReport += (re, media, report) => { /* Optional: log performance */ };
        }

        public async Task StartScreenShareAsync()
        {
            await InitializeAsync();
            if (_isStreaming) StopStream();

            _screenSource = new SIPSorceryMedia.Encoders.VideoEncoderEndPoint();
            _videoTrack = new MediaStreamTrack(_screenSource.GetVideoSourceFormats(), MediaStreamStatusEnum.SendOnly);
            _peerConnection?.addTrack(_videoTrack);

            _screenSource.OnVideoSourceEncodedSample += (duration, sample) => _peerConnection?.SendVideo(duration, sample);
            
            _isStreaming = true;
            // Screen capture loop using DXGI Desktop Duplication API
            _ = Task.Run(async () => {
                try {
                    using var factory = new Factory1();
                    using var adapter = factory.GetAdapter1(0);
                    using var device = new Device(adapter);
                    using var output = adapter.GetOutput(0);
                    using var output1 = output.QueryInterface<Output1>();
                    
                    int width = output1.Description.DesktopBounds.Right - output1.Description.DesktopBounds.Left;
                    int height = output1.Description.DesktopBounds.Bottom - output1.Description.DesktopBounds.Top;

                    using var duplicatedOutput = output1.DuplicateOutput(device);
                    
                    var textureDesc = new Texture2DDescription
                    {
                        CpuAccessFlags = CpuAccessFlags.Read,
                        BindFlags = BindFlags.None,
                        Format = Format.B8G8R8A8_UNorm,
                        Width = width,
                        Height = height,
                        OptionFlags = ResourceOptionFlags.None,
                        MipLevels = 1,
                        ArraySize = 1,
                        SampleDescription = { Count = 1, Quality = 0 },
                        Usage = ResourceUsage.Staging
                    };
                    using var screenTexture = new Texture2D(device, textureDesc);
                    byte[] buffer = new byte[width * height * 4];

                    while (_isStreaming && _peerConnection != null) {
                        try {
                            SharpDX.DXGI.Resource screenResource;
                            OutputDuplicateFrameInformation duplicateFrameInformation;
                            
                            // Try to acquire next frame with 33ms timeout (~30 FPS)
                            if (duplicatedOutput.TryAcquireNextFrame(33, out duplicateFrameInformation, out screenResource).Failure) {
                                continue;
                            }

                            using (var screenTexture2D = screenResource.QueryInterface<Texture2D>()) {
                                device.ImmediateContext.CopyResource(screenTexture2D, screenTexture);
                            }
                            screenResource.Dispose();
                            duplicatedOutput.ReleaseFrame();

                            var mapSource = device.ImmediateContext.MapSubresource(screenTexture, 0, MapMode.Read, MapFlags.None);
                            try {
                                int stride = mapSource.RowPitch;
                                int targetStride = width * 4;
                                
                                if (stride == targetStride) {
                                    Marshal.Copy(mapSource.DataPointer, buffer, 0, buffer.Length);
                                } else {
                                    for (int y = 0; y < height; y++) {
                                        Marshal.Copy(IntPtr.Add(mapSource.DataPointer, y * stride), buffer, y * targetStride, targetStride);
                                    }
                                }
                                
                                _screenSource.ExternalVideoSourceRawSample(1000/30, width, height, buffer, VideoPixelFormatsEnum.Bgra);
                            } finally {
                                device.ImmediateContext.UnmapSubresource(screenTexture, 0);
                            }
                        } catch (SharpDXException ex) {
                            if (ex.ResultCode.Code == SharpDX.DXGI.ResultCode.AccessLost.Code) {
                                Console.WriteLine("[WebRTC] DXGI Access Lost. Stream will stop.");
                                break;
                            }
                        }
                    }
                } catch (Exception ex) {
                    Console.WriteLine($"[WebRTC] DXGI Capture error: {ex}");
                }
            });

            await StartCallAsync();
        }

        public async Task StartCameraAsync()
        {
            await InitializeAsync();
            if (_isStreaming) StopStream();

            _camSource = new WindowsVideoEndPoint(new VpxVideoEncoder()); 
            // By default WindowsVideoEndPoint picks the first camera
            _videoTrack = new MediaStreamTrack(_camSource.GetVideoSourceFormats(), MediaStreamStatusEnum.SendOnly);
            _peerConnection?.addTrack(_videoTrack);

            _camSource.OnVideoSourceEncodedSample += (duration, sample) => _peerConnection?.SendVideo(duration, sample);
            
            await _camSource.StartVideo();
            _isStreaming = true;

            await StartCallAsync();
        }

        public void StopStream()
        {
            _isStreaming = false;
            _camSource?.CloseVideo();
            _camSource = null;
            _screenSource?.CloseVideo();
            _screenSource = null;
        }

        // Removed legacy GDI capture methods (CaptureScreen, BitmapToRawBuffer)

        public async Task HandleSignalingMessageAsync(JObject msg)
        {
            if (_peerConnection == null) await InitializeAsync();

            var type = msg["type"]?.ToString();
            switch (type)
            {
                case "webrtc_offer":
                    var offerSdp = msg["sdp"]?.ToString();
                    if (!string.IsNullOrEmpty(offerSdp))
                    {
                        var result = _peerConnection.setRemoteDescription(new RTCSessionDescriptionInit { sdp = offerSdp, type = RTCSdpType.offer });
                        if (result == SetDescriptionResultEnum.OK)
                        {
                            var answer = _peerConnection.createAnswer(null);
                            await _peerConnection.setLocalDescription(answer);
                            _sendSignalingMessage(new { type = "webrtc_answer", sdp = answer.sdp });
                        }
                    }
                    break;

                case "webrtc_answer":
                    var answerSdp = msg["sdp"]?.ToString();
                    if (!string.IsNullOrEmpty(answerSdp))
                    {
                        _peerConnection.setRemoteDescription(new RTCSessionDescriptionInit { sdp = answerSdp, type = RTCSdpType.answer });
                    }
                    break;

                case "webrtc_ice":
                    var candidateVal = msg["candidate"]?.ToString();
                    var sdpMid = msg["sdpMid"]?.ToString();
                    ushort sdpMLineIndex = 0;
                    if (msg["sdpMLineIndex"] != null && ushort.TryParse(msg["sdpMLineIndex"]?.ToString(), out var parsedIndex))
                    {
                        sdpMLineIndex = parsedIndex;
                    }

                    if (!string.IsNullOrEmpty(candidateVal))
                    {
                        var init = new RTCIceCandidateInit
                        {
                            candidate = candidateVal,
                            sdpMid = sdpMid,
                            sdpMLineIndex = sdpMLineIndex
                        };
                        _peerConnection?.addIceCandidate(init);
                    }
                    break;
            }
        }

        public async Task StartCallAsync()
        {
            if (_peerConnection == null) await InitializeAsync();

            var offer = _peerConnection!.createOffer(null);
            await _peerConnection.setLocalDescription(offer);
            _sendSignalingMessage(new { type = "webrtc_offer", sdp = offer.sdp });
        }

        public void Close()
        {
            _peerConnection?.Close("Closed normally");
            _peerConnection = null;
        }
    }
}
