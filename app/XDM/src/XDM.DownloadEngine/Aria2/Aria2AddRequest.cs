namespace XDM.DownloadEngine.Aria2;

public sealed record Aria2AddRequest(
    Uri Source,
    string DestinationDirectory,
    string? FileName = null,
    IReadOnlyDictionary<string, string>? Headers = null,
    string? Username = null,
    string? Password = null,
    long? SpeedLimitBytesPerSecond = null)
{
    public Aria2AddRequest Normalize()
    {
        ArgumentNullException.ThrowIfNull(Source);
        if (!Source.IsAbsoluteUri)
        {
            throw new ArgumentException("The aria2 source URI must be absolute.", nameof(Source));
        }

        string scheme = Source.Scheme.ToLowerInvariant();
        if (scheme is not ("http" or "https" or "ftp" or "sftp" or "magnet"))
        {
            throw new NotSupportedException($"aria2 source scheme '{Source.Scheme}' is not supported by this integration.");
        }

        string directory = string.IsNullOrWhiteSpace(DestinationDirectory)
            ? Environment.GetFolderPath(Environment.SpecialFolder.UserProfile)
            : Path.GetFullPath(Environment.ExpandEnvironmentVariables(DestinationDirectory.Trim()));
        string? fileName = string.IsNullOrWhiteSpace(FileName) ? null : FileName.Trim();
        IReadOnlyDictionary<string, string> headers = Headers?
            .Where(static pair => !string.IsNullOrWhiteSpace(pair.Key))
            .ToDictionary(
                static pair => pair.Key.Trim(),
                static pair => pair.Value?.Trim() ?? string.Empty,
                StringComparer.OrdinalIgnoreCase)
            ?? new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);

        return this with
        {
            DestinationDirectory = directory,
            FileName = fileName,
            Headers = headers,
            Username = string.IsNullOrWhiteSpace(Username) ? null : Username.Trim(),
            Password = string.IsNullOrWhiteSpace(Password) ? null : Password,
            SpeedLimitBytesPerSecond = SpeedLimitBytesPerSecond is > 0 ? SpeedLimitBytesPerSecond : null
        };
    }
}
