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
    /// Flow:
    ///   1. First launch → CreatePair() → saves pairId to local settings
    ///   2. QR code contains pairId (instead of raw IP)
    ///   3. Android scans QR → connects relay with same pairId as "mobile"
    ///   4. Both sides talk through relay regardless of network
    /// </summary>
    public class PairingService
    {
        private const string RelayBaseUrl = "https://nexlink-relay.onrender.com";
        private static readonly HttpClient _http = new() { Timeout = TimeSpan.FromSeconds(15) };

        // Local settings key for persisted pairId
        private const string PairIdSettingsKey = "NexLink_PairId";

        /// <summary>
        /// Gets or creates a permanent pairId for this machine.
        /// Stored in user's AppData so it survives app restarts.
        /// </summary>
        public static async Task<string> GetOrCreatePairIdAsync(string userId = "anonymous")
        {
            // Check saved pair
            var saved = LoadSavedPairId();
            if (!string.IsNullOrEmpty(saved))
            {
                Console.WriteLine($"[Pairing] Using saved pairId: {saved}");
                return saved;
            }

            // Create new pair on relay server
            return await CreatePairAsync(userId, Environment.MachineName);
        }

        public static async Task<string> CreatePairAsync(string userId, string deviceName)
        {
            try
            {
                var body = JsonConvert.SerializeObject(new { userId, deviceName });
                var content = new StringContent(body, Encoding.UTF8, "application/json");
                var resp = await _http.PostAsync($"{RelayBaseUrl}/pair/create", content);
                resp.EnsureSuccessStatusCode();
                var json = JObject.Parse(await resp.Content.ReadAsStringAsync());
                var pairId = json["pairId"]?.ToString() ?? "";
                if (!string.IsNullOrEmpty(pairId))
                {
                    SavePairId(pairId);
                    Console.WriteLine($"[Pairing] New pair created: {pairId}");
                }
                return pairId;
            }
            catch (Exception ex)
            {
                Console.WriteLine($"[Pairing] CreatePair failed: {ex.Message}");
                // Fallback: generate local pair id so relay can still work when server wakes up
                var fallbackId = Guid.NewGuid().ToString();
                SavePairId(fallbackId);
                return fallbackId;
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

        // ─── Local persistence ────────────────────────────────────────────────
        private static string SettingsPath =>
            System.IO.Path.Combine(
                Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData),
                "NexLink", "settings.json");

        public static string LoadSavedPairId()
        {
            try
            {
                if (System.IO.File.Exists(SettingsPath))
                {
                    var json = System.IO.File.ReadAllText(SettingsPath);
                    var obj = JObject.Parse(json);
                    return obj[PairIdSettingsKey]?.ToString() ?? "";
                }
            }
            catch { }
            return "";
        }

        public static void SavePairId(string pairId)
        {
            try
            {
                var dir = System.IO.Path.GetDirectoryName(SettingsPath)!;
                System.IO.Directory.CreateDirectory(dir);
                JObject obj = new();
                if (System.IO.File.Exists(SettingsPath))
                    obj = JObject.Parse(System.IO.File.ReadAllText(SettingsPath));
                obj[PairIdSettingsKey] = pairId;
                System.IO.File.WriteAllText(SettingsPath, obj.ToString());
            }
            catch { }
        }

        public static void ClearPairId()
        {
            try
            {
                if (System.IO.File.Exists(SettingsPath))
                {
                    var obj = JObject.Parse(System.IO.File.ReadAllText(SettingsPath));
                    obj.Remove(PairIdSettingsKey);
                    System.IO.File.WriteAllText(SettingsPath, obj.ToString());
                }
            }
            catch { }
        }
    }
}
