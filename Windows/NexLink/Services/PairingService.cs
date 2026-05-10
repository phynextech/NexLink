using System;
using System.Net.Http;
using System.Text;
using System.Threading.Tasks;
using Newtonsoft.Json;
using Newtonsoft.Json.Linq;

namespace NexLink.Services
{
    /// <summary>
    /// Manages device pairing with the NexLink cloud relay server.
    ///
    /// Cloud-only flow:
    ///   1. First launch → LoadOrCreateIdentity() generates userId + deviceId
    ///   2. GetOrCreatePairIdAsync() registers device on relay, returns pairId
    ///   3. GenerateQRCode produces { userId, deviceId, pairId, relayUrl }
    ///   4. Android scans QR and connects to relay using same userId:deviceId room key
    ///   5. All subsequent launches auto-load from settings — no QR re-scan
    /// </summary>
    public class PairingService
    {
        private const string RelayBaseUrl = "https://nexlink-1.onrender.com";
        private static readonly HttpClient _http = new() { Timeout = TimeSpan.FromSeconds(15) };

        // Settings keys
        private const string KeyPairId   = "NexLink_PairId";
        private const string KeyUserId   = "NexLink_UserId";
        private const string KeyDeviceId = "NexLink_DeviceId";

        // ─── Identity management ──────────────────────────────────────────

        /// <summary>
        /// Load persisted userId + deviceId, or create new ones on first launch.
        /// </summary>
        public static (string userId, string deviceId) LoadOrCreateIdentity()
        {
            var settings = LoadSettings();

            var userId   = settings[KeyUserId]?.ToString()   ?? "";
            var deviceId = settings[KeyDeviceId]?.ToString() ?? "";

            if (string.IsNullOrEmpty(userId))
            {
                userId = $"pc_{Guid.NewGuid():N}";
                settings[KeyUserId] = userId;
            }
            if (string.IsNullOrEmpty(deviceId))
            {
                deviceId = Guid.NewGuid().ToString("N");
                settings[KeyDeviceId] = deviceId;
            }

            SaveSettings(settings);
            Console.WriteLine($"[Pairing] Identity: userId={userId[..12]}… deviceId={deviceId[..8]}…");
            return (userId, deviceId);
        }

        /// <summary>
        /// Gets or creates a permanent pairId for this (userId, deviceId) combination.
        /// </summary>
        public static async Task<string> GetOrCreatePairIdAsync(
            string userId, string deviceId, string deviceName = "")
        {
            var saved = LoadSavedPairId();
            if (!string.IsNullOrEmpty(saved))
            {
                Console.WriteLine($"[Pairing] Reusing pairId: {saved}");
                return saved;
            }

            return await CreatePairAsync(userId, deviceId, deviceName);
        }

        public static async Task<string> CreatePairAsync(
            string userId, string deviceId, string deviceName = "")
        {
            try
            {
                var body    = JsonConvert.SerializeObject(new { userId, deviceId, deviceName = deviceName.Length > 0 ? deviceName : Environment.MachineName });
                var content = new StringContent(body, Encoding.UTF8, "application/json");
                var resp    = await _http.PostAsync($"{RelayBaseUrl}/pair/create", content);
                resp.EnsureSuccessStatusCode();

                var json   = JObject.Parse(await resp.Content.ReadAsStringAsync());
                var pairId = json["pairId"]?.ToString() ?? "";
                if (!string.IsNullOrEmpty(pairId))
                {
                    SavePairId(pairId);
                    Console.WriteLine($"[Pairing] Created pairId: {pairId}");
                }
                return pairId;
            }
            catch (Exception ex)
            {
                Console.WriteLine($"[Pairing] CreatePair failed: {ex.Message}");
                var fallback = Guid.NewGuid().ToString();
                SavePairId(fallback);
                return fallback;
            }
        }

        public static async Task<bool> VerifyPairAsync(string pairId)
        {
            try
            {
                var resp = await _http.GetAsync($"{RelayBaseUrl}/pair/{pairId}");
                return resp.IsSuccessStatusCode;
            }
            catch
            {
                return false;
            }
        }

        // ─── Local persistence ─────────────────────────────────────────────

        private static string SettingsPath =>
            System.IO.Path.Combine(
                Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData),
                "NexLink", "settings.json");

        private static JObject LoadSettings()
        {
            try
            {
                if (System.IO.File.Exists(SettingsPath))
                    return JObject.Parse(System.IO.File.ReadAllText(SettingsPath));
            }
            catch { }
            return new JObject();
        }

        private static void SaveSettings(JObject obj)
        {
            try
            {
                var dir = System.IO.Path.GetDirectoryName(SettingsPath)!;
                System.IO.Directory.CreateDirectory(dir);
                System.IO.File.WriteAllText(SettingsPath, obj.ToString());
            }
            catch { }
        }

        public static string LoadSavedPairId()
        {
            return LoadSettings()[KeyPairId]?.ToString() ?? "";
        }

        public static void SavePairId(string pairId)
        {
            var obj = LoadSettings();
            obj[KeyPairId] = pairId;
            SaveSettings(obj);
        }

        public static void ClearPairId()
        {
            var obj = LoadSettings();
            obj.Remove(KeyPairId);
            SaveSettings(obj);
        }
    }
}
