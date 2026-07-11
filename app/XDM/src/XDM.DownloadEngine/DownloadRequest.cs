namespace XDM.DownloadEngine;

public sealed record DownloadRequest(Uri Source, string DestinationDirectory, string? FileName = null)
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
