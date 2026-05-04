using System;
using NAudio.Wave;

namespace NexLink.Services
{
    public class AudioStreamService
    {
        private WaveInEvent? _waveIn;

        public void StartStreaming(Action<string> onAudioChunk)
        {
            try
            {
                if (_waveIn != null) return;
                
                _waveIn = new WaveInEvent
                {
                    DeviceNumber = 0,
                    WaveFormat = new WaveFormat(16000, 1) // 16kHz, mono, 16-bit
                };
                
                _waveIn.DataAvailable += (s, a) =>
                {
                    if (a.BytesRecorded > 0)
                    {
                        var b64 = Convert.ToBase64String(a.Buffer, 0, a.BytesRecorded);
                        onAudioChunk(b64);
                    }
                };
                
                _waveIn.StartRecording();
            }
            catch (Exception ex)
            {
                Console.WriteLine($"[AudioStreamService] Error: {ex.Message}");
            }
        }

        public void StopStreaming()
        {
            try
            {
                _waveIn?.StopRecording();
                _waveIn?.Dispose();
                _waveIn = null;
            }
            catch { }
        }
    }
}
