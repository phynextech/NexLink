using Microsoft.Win32;
using System;
using System.Reflection;

namespace NexLink.Helpers
{
    public static class RegistryHelper
    {
        private const string RunKeyName = @"SOFTWARE\Microsoft\Windows\CurrentVersion\Run";
        private const string AppName = "NexLink";

        public static bool IsAutoRunEnabled()
        {
            try
            {
                using var key = Registry.CurrentUser.OpenSubKey(RunKeyName, false);
                if (key != null)
                {
                    var val = key.GetValue(AppName);
                    return val != null;
                }
            }
            catch { }
            return false;
        }

        public static void SetAutoRun(bool enable)
        {
            try
            {
                using var key = Registry.CurrentUser.OpenSubKey(RunKeyName, true);
                if (key != null)
                {
                    if (enable)
                    {
                        string exePath = Assembly.GetExecutingAssembly().Location;
                        // For .NET Core/5+, Location might be the .dll. We want the .exe
                        if (exePath.EndsWith(".dll", StringComparison.OrdinalIgnoreCase))
                        {
                            exePath = exePath.Substring(0, exePath.Length - 4) + ".exe";
                        }
                        key.SetValue(AppName, $"\"{exePath}\" --hidden");
                    }
                    else
                    {
                        key.DeleteValue(AppName, false);
                    }
                }
            }
            catch (Exception ex)
            {
                Console.WriteLine($"[RegistryHelper] SetAutoRun Error: {ex.Message}");
            }
        }
    }
}
