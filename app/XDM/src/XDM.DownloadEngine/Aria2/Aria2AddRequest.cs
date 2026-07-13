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
    public IReadOnlyList<Uri>? Mirrors { get; init; }

    public string? ExpectedChecksumAlgorithm { get; init; }

    public string? ExpectedChecksum { get; init; }

    public IReadOnlyList<Uri> GetSources()
        => new[] { Source }
            .Concat(Mirrors ?? Array.Empty<Uri>())
            .DistinctBy(static uri => uri.AbsoluteUri, StringComparer.OrdinalIgnoreCase)
            .Take(32)
            .ToArray();

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
        Uri[] mirrors = (Mirrors ?? Array.Empty<Uri>())
            .Where(static uri => uri is { IsAbsoluteUri: true })
            .Where(static uri => uri.Scheme is "http" or "https" or "ftp")
            .Where(uri => uri != Source)
            .DistinctBy(static uri => uri.AbsoluteUri, StringComparer.OrdinalIgnoreCase)
            .Take(31)
            .ToArray();
        string? checksumAlgorithm = string.IsNullOrWhiteSpace(ExpectedChecksumAlgorithm)
            ? null
            : ExpectedChecksumAlgorithm.Trim().ToUpperInvariant() switch
            {
                "SHA256" or "SHA-256" => "sha-256",
                "SHA512" or "SHA-512" => "sha-512",
                _ => null
            };
        string? checksum = checksumAlgorithm is null || string.IsNullOrWhiteSpace(ExpectedChecksum)
            ? null
            : new string(ExpectedChecksum
                .Where(static character => !char.IsWhiteSpace(character) && character != ':')
                .Select(char.ToLowerInvariant)
                .ToArray());
        int expectedChecksumLength = checksumAlgorithm == "sha-256" ? 64 : 128;
        if (checksum?.Length != expectedChecksumLength
            || checksum.Any(static character => !Uri.IsHexDigit(character)))
        {
            checksumAlgorithm = null;
            checksum = null;
        }

        return this with
        {
            DestinationDirectory = directory,
            FileName = fileName,
            Headers = headers,
            Username = string.IsNullOrWhiteSpace(Username) ? null : Username.Trim(),
            Password = string.IsNullOrWhiteSpace(Password) ? null : Password,
            SpeedLimitBytesPerSecond = SpeedLimitBytesPerSecond is > 0 ? SpeedLimitBytesPerSecond : null,
            Mirrors = mirrors,
            ExpectedChecksumAlgorithm = checksumAlgorithm,
            ExpectedChecksum = checksum
        };
    }
}
