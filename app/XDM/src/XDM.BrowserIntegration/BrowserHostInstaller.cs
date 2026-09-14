using System.Runtime.Versioning;
using System.Text;
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
    public const string FirefoxExtensionId = FirefoxExtensionHandoffParser.CanonicalExtensionId;
    public const string LegacyFirefoxExtensionId = "xdm-v8-browser-helper@subhra74.github.io";
    public const string OlderLegacyFirefoxExtensionId = "browser-mon@xdman.sourceforge.net";
    public const string FirefoxDesktopFileName = "xdm-modern.desktop";

    private const string FirefoxRegistrationMarker = "X-XDM-FirefoxExtensionId=" + FirefoxExtensionId;
    private static readonly JsonSerializerOptions JsonOptions = new(JsonSerializerDefaults.Web) { WriteIndented = true };
    private readonly string _nativeHostPath;
    private readonly string _applicationPath;
    private readonly string _homeDirectory;
    private readonly string _localApplicationDataDirectory;
    private readonly BrowserHostPlatform _platform;

    public BrowserHostInstaller()
        : this(
            ResolveNativeHostPath(),
            Environment.GetFolderPath(Environment.SpecialFolder.UserProfile),
            DetectPlatform(),
            Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
            ResolveApplicationPath())
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
        string? localApplicationDataDirectory = null,
        string? applicationPath = null)
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
        _applicationPath = Path.GetFullPath(
            string.IsNullOrWhiteSpace(applicationPath)
                ? InferApplicationPath(_nativeHostPath, platform)
                : applicationPath);
    }

    public BrowserHostInstallationStatus GetStatus()
    {
        bool hostExists = File.Exists(_nativeHostPath);
        bool firefoxRegistered = IsFirefoxProtocolRegistered();
        List<BrowserHostManifestStatus> manifests = GetChromiumManifestTargets()
            .Select(target => InspectChromiumManifest(target.Browser, target.Path))
            .ToList();
        int chromiumCount = manifests.Count(static manifest => manifest.Exists);
        int compatibleCount = manifests.Count(static manifest => manifest.IsCompatible);
        bool chromiumCompatible = chromiumCount == 0
            || (hostExists && compatibleCount == chromiumCount);
        bool compatible = firefoxRegistered && chromiumCompatible;
        string message = $"Firefox custom-protocol registration={(firefoxRegistered ? "ready" : "missing")}; "
            + $"native host={(hostExists ? "found" : "missing")}; Chromium-family manifests={chromiumCount}; compatible Chromium manifests={compatibleCount}.";
        return new BrowserHostInstallationStatus(
            hostExists,
            firefoxRegistered,
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
        string[] extensionIds = ParseChromiumExtensionIds(chromiumExtensionId);
        await RegisterFirefoxProtocolAsync(cancellationToken).ConfigureAwait(false);
        RemoveLegacyFirefoxNativeMessagingManifest();

        if (File.Exists(_nativeHostPath) && extensionIds.Length > 0)
        {
            foreach ((string _, string path) in GetChromiumManifestTargets())
            {
                object manifest = new
                {
                    name = HostName,
                    description = $"XDM native browser integration host (protocol {BrowserNativeProtocol.ProtocolVersion})",
                    path = _nativeHostPath,
                    type = "stdio",
                    allowed_origins = extensionIds.Select(static id => $"chrome-extension://{id}/").ToArray(),
                    xdm_allowed_extension_ids = extensionIds,
                    xdm_protocol_version = BrowserNativeProtocol.ProtocolVersion
                };
                await WriteManifestAsync(path, manifest, cancellationToken).ConfigureAwait(false);
            }

            if (_platform == BrowserHostPlatform.Windows && OperatingSystem.IsWindows())
            {
                RegisterWindowsChromiumManifestPaths();
            }
        }

        return GetStatus();
    }

    public Task<BrowserHostInstallationStatus> UninstallAsync(CancellationToken cancellationToken = default)
    {
        cancellationToken.ThrowIfCancellationRequested();
        foreach ((string _, string path) in GetChromiumManifestTargets())
        {
            if (File.Exists(path))
            {
                File.Delete(path);
            }
        }

        RemoveLegacyFirefoxNativeMessagingManifest();
        UnregisterFirefoxProtocol();
        if (_platform == BrowserHostPlatform.Windows && OperatingSystem.IsWindows())
        {
            UnregisterWindowsChromiumManifestPaths();
        }

        return Task.FromResult(GetStatus());
    }

    private BrowserHostManifestStatus InspectChromiumManifest(string browser, string path)
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
            bool protocolMatches = root.TryGetProperty("xdm_protocol_version", out JsonElement protocolVersion)
                && string.Equals(protocolVersion.GetString(), BrowserNativeProtocol.ProtocolVersion, StringComparison.Ordinal);
            bool allowListCompatible = root.TryGetProperty("allowed_origins", out JsonElement origins)
                && origins.ValueKind == JsonValueKind.Array
                && origins.GetArrayLength() > 0
                && HasExpectedChromiumIdentity(root, origins);
            bool compatible = nameMatches && pathMatches && typeMatches && protocolMatches && allowListCompatible;
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

    private static bool HasExpectedChromiumIdentity(JsonElement root, JsonElement origins)
    {
        string[] originIds = origins.EnumerateArray()
            .Where(static value => value.ValueKind == JsonValueKind.String && IsValidChromiumOrigin(value.GetString()))
            .Select(static value => value.GetString()!["chrome-extension://".Length..^1])
            .ToArray();
        if (originIds.Length == 0 || originIds.Length != origins.GetArrayLength())
        {
            return false;
        }

        string[] expected = ReadManifestExtensionIds(root);
        return expected.Length > 0
            && originIds.OrderBy(static value => value, StringComparer.Ordinal)
                .SequenceEqual(expected.OrderBy(static value => value, StringComparer.Ordinal), StringComparer.Ordinal);
    }

    private static string[] ReadManifestExtensionIds(JsonElement root)
    {
        if (!root.TryGetProperty("xdm_allowed_extension_ids", out JsonElement identities)
            || identities.ValueKind != JsonValueKind.Array)
        {
            return [];
        }

        return identities.EnumerateArray()
            .Where(static value => value.ValueKind == JsonValueKind.String)
            .Select(static value => value.GetString())
            .Where(static value => !string.IsNullOrWhiteSpace(value))
            .Select(static value => value!.Trim())
            .Distinct(StringComparer.Ordinal)
            .ToArray();
    }

    private static bool IsValidChromiumOrigin(string? value)
    {
        const string Prefix = "chrome-extension://";
        if (string.IsNullOrWhiteSpace(value)
            || !value.StartsWith(Prefix, StringComparison.Ordinal)
            || !value.EndsWith('/'))
        {
            return false;
        }

        string id = value[Prefix.Length..^1];
        return id.Length == 32 && id.All(static character => character is >= 'a' and <= 'p');
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

    private IReadOnlyList<(string Browser, string Path)> GetChromiumManifestTargets()
    {
        string fileName = $"{HostName}.json";
        if (_platform == BrowserHostPlatform.Windows)
        {
            string root = Path.Combine(_localApplicationDataDirectory, "XDM", "NativeMessagingHosts");
            return
            [
                ("Chrome", Path.Combine(root, "chrome", fileName)),
                ("Chromium", Path.Combine(root, "chromium", fileName)),
                ("Edge", Path.Combine(root, "edge", fileName)),
                ("Brave", Path.Combine(root, "brave", fileName)),
                ("Vivaldi", Path.Combine(root, "vivaldi", fileName)),
                ("Opera", Path.Combine(root, "opera", fileName))
            ];
        }

        if (_platform == BrowserHostPlatform.MacOS)
        {
            string applicationSupport = Path.Combine(_homeDirectory, "Library", "Application Support");
            return
            [
                ("Chrome", Path.Combine(applicationSupport, "Google", "Chrome", "NativeMessagingHosts", fileName)),
                ("Chromium", Path.Combine(applicationSupport, "Chromium", "NativeMessagingHosts", fileName)),
                ("Edge", Path.Combine(applicationSupport, "Microsoft Edge", "NativeMessagingHosts", fileName)),
                ("Brave", Path.Combine(applicationSupport, "BraveSoftware", "Brave-Browser", "NativeMessagingHosts", fileName)),
                ("Vivaldi", Path.Combine(applicationSupport, "Vivaldi", "NativeMessagingHosts", fileName)),
                ("Opera", Path.Combine(applicationSupport, "com.operasoftware.Opera", "NativeMessagingHosts", fileName))
            ];
        }

        string config = Path.Combine(_homeDirectory, ".config");
        return
        [
            ("Chrome", Path.Combine(config, "google-chrome", "NativeMessagingHosts", fileName)),
            ("Chromium", Path.Combine(config, "chromium", "NativeMessagingHosts", fileName)),
            ("Edge", Path.Combine(config, "microsoft-edge", "NativeMessagingHosts", fileName)),
            ("Brave", Path.Combine(config, "BraveSoftware", "Brave-Browser", "NativeMessagingHosts", fileName)),
            ("Vivaldi", Path.Combine(config, "vivaldi", "NativeMessagingHosts", fileName)),
            ("Opera", Path.Combine(config, "opera", "NativeMessagingHosts", fileName))
        ];
    }

    private async Task RegisterFirefoxProtocolAsync(CancellationToken cancellationToken)
    {
        if (_platform == BrowserHostPlatform.Linux)
        {
            string desktopPath = GetLinuxFirefoxDesktopPath();
            Directory.CreateDirectory(Path.GetDirectoryName(desktopPath)!);
            string temporary = desktopPath + ".tmp";
            string desktop = "[Desktop Entry]\n"
                + "Type=Application\n"
                + "Name=Xtreme Download Manager\n"
                + "Comment=Receive Firefox media handoffs\n"
                + $"Exec={QuoteDesktopExec(_applicationPath)} %u\n"
                + "Terminal=false\n"
                + "NoDisplay=true\n"
                + "MimeType=x-scheme-handler/xdmdownload;x-scheme-handler/xdmdownload-debug;\n"
                + FirefoxRegistrationMarker + "\n";
            await File.WriteAllTextAsync(temporary, desktop, Encoding.UTF8, cancellationToken).ConfigureAwait(false);
            File.Move(temporary, desktopPath, overwrite: true);
            await SetLinuxMimeDefaultsAsync(cancellationToken).ConfigureAwait(false);
            return;
        }

        if (_platform == BrowserHostPlatform.Windows && OperatingSystem.IsWindows())
        {
            RegisterWindowsProtocol(FirefoxExtensionHandoffParser.ReleaseScheme);
            RegisterWindowsProtocol(FirefoxExtensionHandoffParser.DebugScheme);
        }
        // macOS protocol handlers are declared by the application bundle. XDM currently publishes
        // portable/deb and Windows artifacts only; the final packaging gate owns a future .app bundle.
    }

    private bool IsFirefoxProtocolRegistered()
    {
        if (_platform == BrowserHostPlatform.Linux)
        {
            string desktopPath = GetLinuxFirefoxDesktopPath();
            if (!File.Exists(desktopPath))
            {
                return false;
            }

            string content = File.ReadAllText(desktopPath);
            string mimeApps = GetLinuxMimeAppsPath();
            if (!content.Contains(FirefoxRegistrationMarker, StringComparison.Ordinal)
                || !content.Contains("x-scheme-handler/xdmdownload;", StringComparison.Ordinal)
                || !content.Contains("x-scheme-handler/xdmdownload-debug;", StringComparison.Ordinal)
                || !content.Contains(QuoteDesktopExec(_applicationPath), StringComparison.Ordinal)
                || !File.Exists(mimeApps))
            {
                return false;
            }

            string defaults = File.ReadAllText(mimeApps);
            return defaults.Contains("x-scheme-handler/xdmdownload=xdm-modern.desktop", StringComparison.Ordinal)
                && defaults.Contains("x-scheme-handler/xdmdownload-debug=xdm-modern.desktop", StringComparison.Ordinal);
        }

        if (_platform == BrowserHostPlatform.Windows && OperatingSystem.IsWindows())
        {
            return IsWindowsProtocolRegistered(FirefoxExtensionHandoffParser.ReleaseScheme)
                && IsWindowsProtocolRegistered(FirefoxExtensionHandoffParser.DebugScheme);
        }

        return false;
    }

    private void UnregisterFirefoxProtocol()
    {
        if (_platform == BrowserHostPlatform.Linux)
        {
            string desktopPath = GetLinuxFirefoxDesktopPath();
            if (File.Exists(desktopPath)
                && File.ReadAllText(desktopPath).Contains(FirefoxRegistrationMarker, StringComparison.Ordinal))
            {
                File.Delete(desktopPath);
            }

            RemoveLinuxMimeDefaults();
            return;
        }

        if (_platform == BrowserHostPlatform.Windows && OperatingSystem.IsWindows())
        {
            UnregisterWindowsProtocol(FirefoxExtensionHandoffParser.ReleaseScheme);
            UnregisterWindowsProtocol(FirefoxExtensionHandoffParser.DebugScheme);
        }
    }

    private string GetLinuxFirefoxDesktopPath()
        => Path.Combine(_homeDirectory, ".local", "share", "applications", FirefoxDesktopFileName);

    private string GetLinuxMimeAppsPath()
        => Path.Combine(_homeDirectory, ".config", "mimeapps.list");

    private async Task SetLinuxMimeDefaultsAsync(CancellationToken cancellationToken)
    {
        string path = GetLinuxMimeAppsPath();
        Directory.CreateDirectory(Path.GetDirectoryName(path)!);
        List<string> lines = File.Exists(path)
            ? (await File.ReadAllLinesAsync(path, cancellationToken).ConfigureAwait(false)).ToList()
            : [];
        SetMimeDefault(lines, "x-scheme-handler/xdmdownload", FirefoxDesktopFileName);
        SetMimeDefault(lines, "x-scheme-handler/xdmdownload-debug", FirefoxDesktopFileName);
        string temporary = path + ".tmp";
        await File.WriteAllLinesAsync(temporary, lines, Encoding.UTF8, cancellationToken).ConfigureAwait(false);
        File.Move(temporary, path, overwrite: true);
    }

    private void RemoveLinuxMimeDefaults()
    {
        string path = GetLinuxMimeAppsPath();
        if (!File.Exists(path))
        {
            return;
        }

        string[] keys = ["x-scheme-handler/xdmdownload=", "x-scheme-handler/xdmdownload-debug="];
        List<string> lines = File.ReadAllLines(path)
            .Where(line => !keys.Any(key => line.StartsWith(key, StringComparison.Ordinal)
                && line.Contains(FirefoxDesktopFileName, StringComparison.Ordinal)))
            .ToList();
        File.WriteAllLines(path, lines, Encoding.UTF8);
    }

    private static void SetMimeDefault(List<string> lines, string key, string desktopFile)
    {
        int section = lines.FindIndex(static line => line.Equals("[Default Applications]", StringComparison.Ordinal));
        if (section < 0)
        {
            if (lines.Count > 0 && lines[^1].Length != 0)
            {
                lines.Add(string.Empty);
            }

            lines.Add("[Default Applications]");
            section = lines.Count - 1;
        }

        int end = lines.FindIndex(section + 1, static line => line.StartsWith('['));
        if (end < 0)
        {
            end = lines.Count;
        }

        string prefix = key + "=";
        int existing = lines.FindIndex(section + 1, end - section - 1, line => line.StartsWith(prefix, StringComparison.Ordinal));
        string value = prefix + desktopFile;
        if (existing >= 0)
        {
            lines[existing] = value;
        }
        else
        {
            lines.Insert(end, value);
        }
    }

    private void RemoveLegacyFirefoxNativeMessagingManifest()
    {
        string path = GetLegacyFirefoxManifestPath();
        if (File.Exists(path))
        {
            File.Delete(path);
        }

        if (_platform == BrowserHostPlatform.Windows && OperatingSystem.IsWindows())
        {
            Registry.CurrentUser.DeleteSubKeyTree($@"Software\Mozilla\NativeMessagingHosts\{HostName}", throwOnMissingSubKey: false);
        }
    }

    private string GetLegacyFirefoxManifestPath()
    {
        string fileName = $"{HostName}.json";
        return _platform switch
        {
            BrowserHostPlatform.Windows => Path.Combine(_localApplicationDataDirectory, "XDM", "NativeMessagingHosts", "firefox", fileName),
            BrowserHostPlatform.MacOS => Path.Combine(_homeDirectory, "Library", "Application Support", "Mozilla", "NativeMessagingHosts", fileName),
            _ => Path.Combine(_homeDirectory, ".mozilla", "native-messaging-hosts", fileName)
        };
    }

    private static string QuoteDesktopExec(string path)
        => '"' + path.Replace("\\", "\\\\", StringComparison.Ordinal).Replace("\"", "\\\"", StringComparison.Ordinal) + '"';

    [SupportedOSPlatform("windows")]
    private void RegisterWindowsProtocol(string scheme)
    {
        using RegistryKey key = Registry.CurrentUser.CreateSubKey($@"Software\Classes\{scheme}", writable: true)
            ?? throw new InvalidOperationException($"Could not register {scheme} protocol.");
        key.SetValue(null, "URL:XDM Firefox bridge", RegistryValueKind.String);
        key.SetValue("URL Protocol", string.Empty, RegistryValueKind.String);
        using RegistryKey command = key.CreateSubKey(@"shell\open\command", writable: true)
            ?? throw new InvalidOperationException($"Could not register {scheme} command.");
        command.SetValue(null, $"\"{_applicationPath}\" \"%1\"", RegistryValueKind.String);
    }

    [SupportedOSPlatform("windows")]
    private bool IsWindowsProtocolRegistered(string scheme)
    {
        using RegistryKey? key = Registry.CurrentUser.OpenSubKey($@"Software\Classes\{scheme}");
        using RegistryKey? command = key?.OpenSubKey(@"shell\open\command");
        return key?.GetValue("URL Protocol") is not null
            && string.Equals(command?.GetValue(null) as string, $"\"{_applicationPath}\" \"%1\"", StringComparison.OrdinalIgnoreCase);
    }

    [SupportedOSPlatform("windows")]
    private static void UnregisterWindowsProtocol(string scheme)
        => Registry.CurrentUser.DeleteSubKeyTree($@"Software\Classes\{scheme}", throwOnMissingSubKey: false);

    [SupportedOSPlatform("windows")]
    private void RegisterWindowsChromiumManifestPaths()
    {
        Dictionary<string, string> manifests = GetChromiumManifestTargets()
            .ToDictionary(static target => target.Browser, static target => target.Path, StringComparer.Ordinal);
        foreach ((string browser, string key) in GetWindowsChromiumRegistryKeys())
        {
            using RegistryKey? registryKey = Registry.CurrentUser.CreateSubKey(key);
            registryKey?.SetValue(null, manifests[browser], RegistryValueKind.String);
        }
    }

    [SupportedOSPlatform("windows")]
    private static void UnregisterWindowsChromiumManifestPaths()
    {
        foreach (string key in GetWindowsChromiumRegistryKeys().Values)
        {
            Registry.CurrentUser.DeleteSubKeyTree(key, throwOnMissingSubKey: false);
        }
    }

    private static Dictionary<string, string> GetWindowsChromiumRegistryKeys()
        => new(StringComparer.Ordinal)
        {
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

    private static string ResolveApplicationPath()
    {
        string fileName = OperatingSystem.IsWindows() ? "XDM.exe" : "XDM";
        string? configured = Environment.GetEnvironmentVariable("XDM_APPLICATION_PATH");
        return string.IsNullOrWhiteSpace(configured)
            ? Path.Combine(AppContext.BaseDirectory, fileName)
            : configured;
    }

    private static string InferApplicationPath(string nativeHostPath, BrowserHostPlatform platform)
    {
        string directory = Path.GetDirectoryName(nativeHostPath) ?? AppContext.BaseDirectory;
        return Path.Combine(directory, platform == BrowserHostPlatform.Windows ? "XDM.exe" : "XDM");
    }
}
