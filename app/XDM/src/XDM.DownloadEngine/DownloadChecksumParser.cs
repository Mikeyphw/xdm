using System.Text.RegularExpressions;

namespace XDM.DownloadEngine;

public sealed record ParsedChecksums(string? Sha256, string? Sha512)
{
    public bool HasAny => Sha256 is not null || Sha512 is not null;
}

public static partial class DownloadChecksumParser
{
    private const int MaximumTextLength = 1024 * 1024;

    [GeneratedRegex("(?<![0-9A-Fa-f])([0-9A-Fa-f]{128})(?![0-9A-Fa-f])", RegexOptions.CultureInvariant)]
    private static partial Regex Sha512Regex();

    [GeneratedRegex("(?<![0-9A-Fa-f])([0-9A-Fa-f]{64})(?![0-9A-Fa-f])", RegexOptions.CultureInvariant)]
    private static partial Regex Sha256Regex();

    public static ParsedChecksums Parse(string text)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(text);
        if (text.Length > MaximumTextLength)
        {
            throw new InvalidDataException("Checksum input exceeds the 1 MiB safety limit.");
        }

        string? sha512 = Sha512Regex().Match(text) is { Success: true } sha512Match
            ? DownloadChecksumService.NormalizeChecksum(sha512Match.Groups[1].Value, DownloadChecksumService.Sha512)
            : null;
        string? sha256 = Sha256Regex().Matches(text)
            .Cast<Match>()
            .Select(static match => match.Groups[1].Value)
            .FirstOrDefault(value => sha512 is null || !sha512.Contains(value, StringComparison.OrdinalIgnoreCase));
        if (sha256 is not null)
        {
            sha256 = DownloadChecksumService.NormalizeChecksum(sha256, DownloadChecksumService.Sha256);
        }

        ParsedChecksums parsed = new(sha256, sha512);
        if (!parsed.HasAny)
        {
            throw new InvalidDataException("No SHA-256 or SHA-512 checksum was found.");
        }
        return parsed;
    }
}
