using System.Runtime.Versioning;
using System.Text.Json;
using Microsoft.Win32;

namespace XDM.BrowserIntegration;

public enum BrowserHostPlatform
{
    Linux,
    MacOS,
    Windows
}

public sealed class BrowserHostInstaller : IBrowserHostInstaller
{
    public const string HostName = "com.xtremedownloadmanager.xdm";
    public const string FirefoxExtensionId = "xdm-v8-browser-helper@subhra74.github.io";
    public const string LegacyFirefoxExtensionId = "browser-mon@xdman.sourceforge.net";

    private static readonly JsonSerializerOptions JsonOptions = new(JsonSerializerDefaults.Web) { WriteIndented = true };
    private readonly string _nativeHostPath;
    private readonly string _homeDirectory;
    private readonly string _localApplicationDataDirectory;
    private readonly BrowserHostPlatform _platform;

    public BrowserHostInstaller()
        : this(
            ResolveNativeHostPath(),
            Environment.GetFolderPath(Environment.SpecialFolder.UserProfile),
            DetectPlatform(),
            Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData))
    {
    }

    public BrowserHostInstaller(string nativeHostPath, string homeDirectory)
        : this(
            nativeHostPath,
            homeDirectory,
            DetectPlatform(),
            Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData))
    {
    }

    public BrowserHostInstaller(
        string nativeHostPath,
        string homeDirectory,
        BrowserHostPlatform platform,
        string? localApplicationDataDirectory = null)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(nativeHostPath);
        ArgumentException.ThrowIfNullOrWhiteSpace(homeDirectory);
        _nativeHostPath = Path.GetFullPath(nativeHostPath);
        _homeDirectory = Path.GetFullPath(homeDirectory);
        _platform = platform;
        _localApplicationDataDirectory = Path.GetFullPath(
            string.IsNullOrWhiteSpace(localApplicationDataDirectory)
                ? homeDirectory
                : localApplicationDataDirectory);
    }

    public BrowserHostInstallationStatus GetStatus()
    {
        bool hostExists = File.Exists(_nativeHostPath);
        List<BrowserHostManifestStatus> manifests = GetManifestTargets()
            .Select(target => InspectManifest(target.Browser, target.Path, target.IsFirefox))
            .ToList();
        bool firefoxInstalled = manifests.Any(static manifest => manifest.Browser == "Firefox" && manifest.Exists);
        int chromiumCount = manifests.Count(static manifest => manifest.Browser != "Firefox" && manifest.Exists);
        int compatibleCount = manifests.Count(static manifest => manifest.IsCompatible);
        bool compatible = hostExists && compatibleCount > 0 && manifests
            .Where(static manifest => manifest.Exists)
            .All(static manifest => manifest.IsCompatible);
        string message = hostExists
            ? $"Native host {(compatible ? "compatible" : "needs repair")}; Firefox={firefoxInstalled}; Chromium-family manifests={chromiumCount}; compatible={compatibleCount}."
            : $"Native host executable is missing: {_nativeHostPath}";
        return new BrowserHostInstallationStatus(
            hostExists,
            firefoxInstalled,
            chromiumCount,
            message,
            compatible,
            compatibleCount,
            manifests);
    }

    public async Task<BrowserHostInstallationStatus> RepairAsync(
        string? chromiumExtensionId,
        CancellationToken cancellationToken = default)
    {
        if (!File.Exists(_nativeHostPath))
        {
            return GetStatus();
        }

        string[] extensionIds = ParseChromiumExtensionIds(chromiumExtensionId);
        foreach ((string browser, string path, bool isFirefox) in GetManifestTargets())
        {
            if (!isFirefox && extensionIds.Length == 0)
            {
                continue;
            }

            object manifest = isFirefox
                ? new
                {
                    name = HostName,
                    description = $"XDM native browser integration host (protocol {BrowserNativeProtocol.ProtocolVersion})",
                    path = _nativeHostPath,
                    type = "stdio",
                    allowed_extensions = new[] { FirefoxExtensionId, LegacyFirefoxExtensionId }
                }
                : new
                {
                    name = HostName,
                    description = $"XDM native browser integration host (protocol {BrowserNativeProtocol.ProtocolVersion})",
                    path = _nativeHostPath,
                    type = "stdio",
                    allowed_origins = extensionIds.Select(static id => $"chrome-extension://{id}/").ToArray()
                };
            await WriteManifestAsync(path, manifest, cancellationToken).ConfigureAwait(false);
            _ = browser;
        }

        if (_platform == BrowserHostPlatform.Windows && OperatingSystem.IsWindows())
        {
            RegisterWindowsManifestPaths();
        }

        return GetStatus();
    }

    public Task<BrowserHostInstallationStatus> UninstallAsync(CancellationToken cancellationToken = default)
    {
        cancellationToken.ThrowIfCancellationRequested();
        foreach ((string Browser, string Path, bool IsFirefox) target in GetManifestTargets())
        {
            if (File.Exists(target.Path))
            {
                File.Delete(target.Path);
            }
        }

        if (_platform == BrowserHostPlatform.Windows && OperatingSystem.IsWindows())
        {
            UnregisterWindowsManifestPaths();
        }

        return Task.FromResult(GetStatus());
    }

    private BrowserHostManifestStatus InspectManifest(string browser, string path, bool isFirefox)
    {
        if (!File.Exists(path))
        {
            return new BrowserHostManifestStatus(browser, path, false, false, "Not installed");
        }

        try
        {
            using JsonDocument document = JsonDocument.Parse(File.ReadAllBytes(path));
            JsonElement root = document.RootElement;
            bool nameMatches = root.TryGetProperty("name", out JsonElement name)
                && string.Equals(name.GetString(), HostName, StringComparison.Ordinal);
            bool pathMatches = root.TryGetProperty("path", out JsonElement executablePath)
                && string.Equals(
                    Path.GetFullPath(executablePath.GetString() ?? string.Empty),
                    _nativeHostPath,
                    _platform == BrowserHostPlatform.Windows ? StringComparison.OrdinalIgnoreCase : StringComparison.Ordinal);
            bool typeMatches = root.TryGetProperty("type", out JsonElement type)
                && string.Equals(type.GetString(), "stdio", StringComparison.Ordinal);
            bool allowListPresent = isFirefox
                ? root.TryGetProperty("allowed_extensions", out JsonElement extensions)
                    && extensions.ValueKind == JsonValueKind.Array
                    && extensions.GetArrayLength() > 0
                : root.TryGetProperty("allowed_origins", out JsonElement origins)
                    && origins.ValueKind == JsonValueKind.Array
                    && origins.GetArrayLength() > 0;
            bool compatible = nameMatches && pathMatches && typeMatches && allowListPresent;
            return new BrowserHostManifestStatus(
                browser,
                path,
                true,
                compatible,
                compatible ? "Compatible" : "Manifest does not match the current host or extension allow-list");
        }
        catch (Exception exception) when (exception is IOException or JsonException or ArgumentException)
        {
            return new BrowserHostManifestStatus(browser, path, true, false, $"Invalid manifest: {exception.Message}");
        }
    }

    private static string[] ParseChromiumExtensionIds(string? value)
    {
        if (string.IsNullOrWhiteSpace(value))
        {
            return [];
        }

        string[] ids = value
            .Split([',', ';', ' ', '\r', '\n'], StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries)
            .Distinct(StringComparer.Ordinal)
            .ToArray();
        foreach (string id in ids)
        {
            if (id.Length != 32 || id.Any(static character => character is < 'a' or > 'p'))
            {
                throw new InvalidDataException($"Chromium extension ID '{id}' is invalid.");
            }
        }

        return ids;
    }

    private static async Task WriteManifestAsync(string path, object manifest, CancellationToken cancellationToken)
    {
        string? directory = Path.GetDirectoryName(path);
        if (!string.IsNullOrEmpty(directory))
        {
            Directory.CreateDirectory(directory);
        }

        string temporary = $"{path}.tmp";
        await File.WriteAllTextAsync(temporary, JsonSerializer.Serialize(manifest, JsonOptions), cancellationToken)
            .ConfigureAwait(false);
        File.Move(temporary, path, overwrite: true);
    }

    private IReadOnlyList<(string Browser, string Path, bool IsFirefox)> GetManifestTargets()
    {
        string fileName = $"{HostName}.json";
        if (_platform == BrowserHostPlatform.Windows)
        {
            string root = Path.Combine(
                _localApplicationDataDirectory,
                "XDM",
                "NativeMessagingHosts");
            return
            [
                ("Firefox", Path.Combine(root, "firefox", fileName), true),
                ("Chrome", Path.Combine(root, "chrome", fileName), false),
                ("Chromium", Path.Combine(root, "chromium", fileName), false),
                ("Edge", Path.Combine(root, "edge", fileName), false),
                ("Brave", Path.Combine(root, "brave", fileName), false),
                ("Vivaldi", Path.Combine(root, "vivaldi", fileName), false),
                ("Opera", Path.Combine(root, "opera", fileName), false)
            ];
        }

        if (_platform == BrowserHostPlatform.MacOS)
        {
            string applicationSupport = Path.Combine(_homeDirectory, "Library", "Application Support");
            return
            [
                ("Firefox", Path.Combine(applicationSupport, "Mozilla", "NativeMessagingHosts", fileName), true),
                ("Chrome", Path.Combine(applicationSupport, "Google", "Chrome", "NativeMessagingHosts", fileName), false),
                ("Chromium", Path.Combine(applicationSupport, "Chromium", "NativeMessagingHosts", fileName), false),
                ("Edge", Path.Combine(applicationSupport, "Microsoft Edge", "NativeMessagingHosts", fileName), false),
                ("Brave", Path.Combine(applicationSupport, "BraveSoftware", "Brave-Browser", "NativeMessagingHosts", fileName), false),
                ("Vivaldi", Path.Combine(applicationSupport, "Vivaldi", "NativeMessagingHosts", fileName), false),
                ("Opera", Path.Combine(applicationSupport, "com.operasoftware.Opera", "NativeMessagingHosts", fileName), false)
            ];
        }

        string config = Path.Combine(_homeDirectory, ".config");
        return
        [
            ("Firefox", Path.Combine(_homeDirectory, ".mozilla", "native-messaging-hosts", fileName), true),
            ("Chrome", Path.Combine(config, "google-chrome", "NativeMessagingHosts", fileName), false),
            ("Chromium", Path.Combine(config, "chromium", "NativeMessagingHosts", fileName), false),
            ("Edge", Path.Combine(config, "microsoft-edge", "NativeMessagingHosts", fileName), false),
            ("Brave", Path.Combine(config, "BraveSoftware", "Brave-Browser", "NativeMessagingHosts", fileName), false),
            ("Vivaldi", Path.Combine(config, "vivaldi", "NativeMessagingHosts", fileName), false),
            ("Opera", Path.Combine(config, "opera", "NativeMessagingHosts", fileName), false)
        ];
    }

    [SupportedOSPlatform("windows")]
    private void RegisterWindowsManifestPaths()
    {
        Dictionary<string, string> keys = GetWindowsRegistryKeys();
        Dictionary<string, string> manifests = GetManifestTargets()
            .ToDictionary(static target => target.Browser, static target => target.Path, StringComparer.Ordinal);
        foreach ((string browser, string key) in keys)
        {
            using RegistryKey? registryKey = Registry.CurrentUser.CreateSubKey(key);
            registryKey?.SetValue(null, manifests[browser], RegistryValueKind.String);
        }
    }

    [SupportedOSPlatform("windows")]
    private static void UnregisterWindowsManifestPaths()
    {
        foreach (string key in GetWindowsRegistryKeys().Values)
        {
            Registry.CurrentUser.DeleteSubKeyTree(key, throwOnMissingSubKey: false);
        }
    }

    private static Dictionary<string, string> GetWindowsRegistryKeys()
        => new(StringComparer.Ordinal)
        {
            ["Firefox"] = $@"Software\Mozilla\NativeMessagingHosts\{HostName}",
            ["Chrome"] = $@"Software\Google\Chrome\NativeMessagingHosts\{HostName}",
            ["Chromium"] = $@"Software\Chromium\NativeMessagingHosts\{HostName}",
            ["Edge"] = $@"Software\Microsoft\Edge\NativeMessagingHosts\{HostName}",
            ["Brave"] = $@"Software\BraveSoftware\Brave-Browser\NativeMessagingHosts\{HostName}",
            ["Vivaldi"] = $@"Software\Vivaldi\NativeMessagingHosts\{HostName}",
            ["Opera"] = $@"Software\Opera Software\NativeMessagingHosts\{HostName}"
        };

    private static BrowserHostPlatform DetectPlatform()
        => OperatingSystem.IsWindows()
            ? BrowserHostPlatform.Windows
            : OperatingSystem.IsMacOS()
                ? BrowserHostPlatform.MacOS
                : BrowserHostPlatform.Linux;

    private static string ResolveNativeHostPath()
    {
        string fileName = OperatingSystem.IsWindows() ? "XDM.NativeHost.exe" : "XDM.NativeHost";
        string? configured = Environment.GetEnvironmentVariable("XDM_NATIVE_HOST_PATH");
        return string.IsNullOrWhiteSpace(configured)
            ? Path.Combine(AppContext.BaseDirectory, fileName)
            : configured;
    }
}
