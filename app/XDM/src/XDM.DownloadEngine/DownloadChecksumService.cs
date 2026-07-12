using System.Security.Cryptography;

namespace XDM.DownloadEngine;

public static class DownloadChecksumService
{
    public const string Sha256 = "SHA-256";
    public const string Sha512 = "SHA-512";

    public static string NormalizeAlgorithm(string? algorithm)
        => algorithm?.Trim().ToUpperInvariant().Replace("_", "-", StringComparison.Ordinal) switch
        {
            "SHA256" or "SHA-256" => Sha256,
            "SHA512" or "SHA-512" => Sha512,
            null or "" => Sha256,
            _ => throw new ArgumentException("Only SHA-256 and SHA-512 checksums are supported.", nameof(algorithm))
        };

    public static string NormalizeChecksum(string checksum, string algorithm)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(checksum);
        string normalizedAlgorithm = NormalizeAlgorithm(algorithm);
        List<char> characters = new(checksum.Length);
        foreach (char character in checksum)
        {
            if (Uri.IsHexDigit(character))
            {
                characters.Add(char.ToUpperInvariant(character));
                continue;
            }

            if (!char.IsWhiteSpace(character) && character != ':')
            {
                throw new ArgumentException(
                    "Checksums may contain hexadecimal characters, whitespace, and colon separators only.",
                    nameof(checksum));
            }
        }

        int expectedLength = normalizedAlgorithm == Sha512 ? 128 : 64;
        if (characters.Count != expectedLength)
        {
            throw new ArgumentException(
                $"{normalizedAlgorithm} checksums must contain {expectedLength} hexadecimal characters.",
                nameof(checksum));
        }

        return new string(characters.ToArray());
    }

    public static async Task<string> ComputeAsync(
        string path,
        string algorithm,
        CancellationToken cancellationToken = default)
    {
        string normalizedAlgorithm = NormalizeAlgorithm(algorithm);
        using IncrementalHash hash = IncrementalHash.CreateHash(
            normalizedAlgorithm == Sha512 ? HashAlgorithmName.SHA512 : HashAlgorithmName.SHA256);
        await using FileStream stream = new(
            path,
            FileMode.Open,
            FileAccess.Read,
            FileShare.Read,
            128 * 1024,
            FileOptions.Asynchronous | FileOptions.SequentialScan);
        byte[] buffer = new byte[128 * 1024];
        while (true)
        {
            int read = await stream.ReadAsync(buffer, cancellationToken).ConfigureAwait(false);
            if (read == 0)
            {
                break;
            }

            hash.AppendData(buffer, 0, read);
        }

        return Convert.ToHexString(hash.GetHashAndReset());
    }
}
