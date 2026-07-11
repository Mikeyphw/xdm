using System.Text.Json;

namespace XDM.BrowserIntegration;

public sealed class BrowserHostInstaller : IBrowserHostInstaller
{
    public const string HostName = "com.xtremedownloadmanager.xdm";
    public const string FirefoxExtensionId = "browser-mon@xdman.sourceforge.net";

    private static readonly JsonSerializerOptions JsonOptions = new(JsonSerializerDefaults.Web) { WriteIndented = true };
    private readonly string _nativeHostPath;
    private readonly string _homeDirectory;

    public BrowserHostInstaller()
        : this(ResolveNativeHostPath(), Environment.GetFolderPath(Environment.SpecialFolder.UserProfile))
    {
    }

    public BrowserHostInstaller(string nativeHostPath, string homeDirectory)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(nativeHostPath);
        ArgumentException.ThrowIfNullOrWhiteSpace(homeDirectory);
        _nativeHostPath = Path.GetFullPath(nativeHostPath);
        _homeDirectory = Path.GetFullPath(homeDirectory);
    }

    public BrowserHostInstallationStatus GetStatus()
    {
        string firefox = GetFirefoxManifestPath();
        int chromiumCount = GetChromiumManifestPaths().Count(File.Exists);
        bool hostExists = File.Exists(_nativeHostPath);
        bool firefoxInstalled = File.Exists(firefox);
        string message = hostExists
            ? $"Native host ready; Firefox={firefoxInstalled}; Chromium manifests={chromiumCount}."
            : $"Native host executable is missing: {_nativeHostPath}";
        return new BrowserHostInstallationStatus(hostExists, firefoxInstalled, chromiumCount, message);
    }

    public async Task<BrowserHostInstallationStatus> RepairAsync(
        string? chromiumExtensionId,
        CancellationToken cancellationToken = default)
    {
        if (!File.Exists(_nativeHostPath))
        {
            return GetStatus();
        }

        await WriteManifestAsync(
            GetFirefoxManifestPath(),
            new
            {
                name = HostName,
                description = "XDM native browser integration host",
                path = _nativeHostPath,
                type = "stdio",
                allowed_extensions = new[] { FirefoxExtensionId }
            },
            cancellationToken).ConfigureAwait(false);

        if (!string.IsNullOrWhiteSpace(chromiumExtensionId))
        {
            string normalized = chromiumExtensionId.Trim();
            foreach (string path in GetChromiumManifestPaths())
            {
                await WriteManifestAsync(
                    path,
                    new
                    {
                        name = HostName,
                        description = "XDM native browser integration host",
                        path = _nativeHostPath,
                        type = "stdio",
                        allowed_origins = new[] { $"chrome-extension://{normalized}/" }
                    },
                    cancellationToken).ConfigureAwait(false);
            }
        }

        return GetStatus();
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

    private string GetFirefoxManifestPath()
        => Path.Combine(_homeDirectory, ".mozilla", "native-messaging-hosts", $"{HostName}.json");

    private IEnumerable<string> GetChromiumManifestPaths()
    {
        string config = Path.Combine(_homeDirectory, ".config");
        string[] roots =
        [
            Path.Combine(config, "google-chrome", "NativeMessagingHosts"),
            Path.Combine(config, "chromium", "NativeMessagingHosts"),
            Path.Combine(config, "BraveSoftware", "Brave-Browser", "NativeMessagingHosts"),
            Path.Combine(config, "microsoft-edge", "NativeMessagingHosts"),
            Path.Combine(config, "vivaldi", "NativeMessagingHosts")
        ];
        return roots.Select(root => Path.Combine(root, $"{HostName}.json"));
    }

    private static string ResolveNativeHostPath()
    {
        string fileName = OperatingSystem.IsWindows() ? "XDM.NativeHost.exe" : "XDM.NativeHost";
        string? configured = Environment.GetEnvironmentVariable("XDM_NATIVE_HOST_PATH");
        return string.IsNullOrWhiteSpace(configured)
            ? Path.Combine(AppContext.BaseDirectory, fileName)
            : configured;
    }
}
