namespace XDM.Core.Settings;

public sealed record Aria2IntegrationSettings(
    bool Enabled,
    Aria2ConnectionMode ConnectionMode,
    string RpcEndpoint,
    string RpcSecret,
    string ExecutablePath,
    string SessionFilePath,
    int PollIntervalMilliseconds,
    int RpcConnectTimeoutSeconds,
    int MaxConcurrentDownloads,
    int SplitCount,
    long MinimumSplitSizeBytes,
    string AdditionalArguments,
    bool AutoStartManagedProcess,
    bool ContinueDownloads,
    bool CheckCertificate,
    bool SaveSession)
{
    public static Aria2IntegrationSettings Default
    {
        get
        {
            string localData = Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData);
            if (string.IsNullOrWhiteSpace(localData))
            {
                localData = Path.GetTempPath();
            }

            return new Aria2IntegrationSettings(
                Enabled: false,
                ConnectionMode: Aria2ConnectionMode.ManagedProcess,
                RpcEndpoint: "http://127.0.0.1:6800/jsonrpc",
                RpcSecret: string.Empty,
                ExecutablePath: "aria2c",
                SessionFilePath: Path.Combine(localData, "XDM", "aria2.session"),
                PollIntervalMilliseconds: 1000,
                RpcConnectTimeoutSeconds: 5,
                MaxConcurrentDownloads: 5,
                SplitCount: 8,
                MinimumSplitSizeBytes: 1024 * 1024,
                AdditionalArguments: string.Empty,
                AutoStartManagedProcess: true,
                ContinueDownloads: true,
                CheckCertificate: true,
                SaveSession: true);
        }
    }

    public Aria2IntegrationSettings Normalize()
    {
        Aria2IntegrationSettings defaults = Default;
        string endpoint = NormalizeEndpoint(RpcEndpoint, ConnectionMode, defaults.RpcEndpoint);
        string executable = string.IsNullOrWhiteSpace(ExecutablePath)
            ? defaults.ExecutablePath
            : ExecutablePath.Trim();
        string sessionFile = string.IsNullOrWhiteSpace(SessionFilePath)
            ? defaults.SessionFilePath
            : Path.GetFullPath(Environment.ExpandEnvironmentVariables(SessionFilePath.Trim()));
        string arguments = string.Join(
            Environment.NewLine,
            (AdditionalArguments ?? string.Empty)
                .Split(["\r\n", "\n"], StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries));

        return this with
        {
            RpcEndpoint = endpoint,
            RpcSecret = (RpcSecret ?? string.Empty).Trim(),
            ExecutablePath = executable,
            SessionFilePath = sessionFile,
            PollIntervalMilliseconds = Math.Clamp(PollIntervalMilliseconds, 250, 30_000),
            RpcConnectTimeoutSeconds = Math.Clamp(RpcConnectTimeoutSeconds, 1, 120),
            MaxConcurrentDownloads = Math.Clamp(MaxConcurrentDownloads, 1, 64),
            SplitCount = Math.Clamp(SplitCount, 1, 64),
            MinimumSplitSizeBytes = Math.Clamp(MinimumSplitSizeBytes, 1024 * 1024, 1024L * 1024 * 1024),
            AdditionalArguments = arguments
        };
    }

    public Uri GetRpcUri()
        => new(Normalize().RpcEndpoint, UriKind.Absolute);

    private static string NormalizeEndpoint(
        string? value,
        Aria2ConnectionMode connectionMode,
        string fallback)
    {
        if (!Uri.TryCreate(value?.Trim(), UriKind.Absolute, out Uri? uri)
            || (uri.Scheme != Uri.UriSchemeHttp && uri.Scheme != Uri.UriSchemeHttps))
        {
            uri = new Uri(fallback, UriKind.Absolute);
        }

        if (connectionMode == Aria2ConnectionMode.ManagedProcess && !uri.IsLoopback)
        {
            uri = new Uri(fallback, UriKind.Absolute);
        }

        UriBuilder builder = new(uri);
        if (string.IsNullOrWhiteSpace(builder.Path) || builder.Path == "/")
        {
            builder.Path = "/jsonrpc";
        }

        return builder.Uri.AbsoluteUri;
    }
}
