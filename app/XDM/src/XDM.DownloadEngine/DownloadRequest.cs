namespace XDM.DownloadEngine;

public sealed record DownloadRequest(
    Uri Source,
    string DestinationDirectory,
    string? FileName = null,
    IReadOnlyDictionary<string, string>? Headers = null,
    string? Username = null,
    string? Password = null,
    string? Cookie = null,
    string? Referer = null,
    string? UserAgent = null,
    string? QueueId = null,
    string? CategoryId = null,
    long? SpeedLimitBytesPerSecond = null,
    DuplicateFileBehavior DuplicateBehavior = DuplicateFileBehavior.AutoRename,
    int ConnectionCount = 4,
    string Method = "GET",
    byte[]? RequestBody = null,
    string? RequestBodyContentType = null,
    XDM.Core.Downloads.DownloadPriority Priority = XDM.Core.Downloads.DownloadPriority.Normal,
    Uri? SourcePage = null,
    IReadOnlyList<Uri>? Mirrors = null,
    string? ExpectedChecksumAlgorithm = null,
    string? ExpectedChecksum = null,
    long? ExpectedLength = null,
    XDM.Core.Downloads.DownloadBackendPreference BackendPreference = XDM.Core.Downloads.DownloadBackendPreference.Automatic,
    bool AllowBackendFallback = true,
    IReadOnlyList<string>? Tags = null,
    bool ApplyDestinationRules = true,
    bool AllowDuplicateUrl = false,
    string? ExpectedSha256 = null,
    string? ExpectedSha512 = null)
{
    public string ResolveFileName()
    {
        if (!string.IsNullOrWhiteSpace(FileName))
        {
            return SanitizeFileName(FileName);
        }

        string candidate = Uri.UnescapeDataString(Path.GetFileName(Source.LocalPath));
        return string.IsNullOrWhiteSpace(candidate)
            ? $"download-{Guid.NewGuid():N}.bin"
            : SanitizeFileName(candidate);
    }

    private static string SanitizeFileName(string fileName)
    {
        char[] invalid = Path.GetInvalidFileNameChars();
        string sanitized = new(fileName.Select(character =>
            invalid.Contains(character) ? '_' : character).ToArray());

        return string.IsNullOrWhiteSpace(sanitized)
            ? $"download-{Guid.NewGuid():N}.bin"
            : sanitized;
    }
}
