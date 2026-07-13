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

    public static Task<string> ComputeAsync(
        string path,
        string algorithm,
        CancellationToken cancellationToken = default)
        => ComputeAsync(path, algorithm, progress: null, cancellationToken);

    public static async Task<string> ComputeAsync(
        string path,
        string algorithm,
        IProgress<(long Processed, long Total)>? progress,
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
        long total = stream.Length;
        long processed = 0;
        byte[] buffer = new byte[128 * 1024];
        while (true)
        {
            int read = await stream.ReadAsync(buffer, cancellationToken).ConfigureAwait(false);
            if (read == 0)
            {
                break;
            }

            hash.AppendData(buffer, 0, read);
            processed += read;
            progress?.Report((processed, total));
        }

        return Convert.ToHexString(hash.GetHashAndReset());
    }

    public static async Task<(string? Sha256, string? Sha512)> ComputeSetAsync(
        string path,
        bool includeSha256,
        bool includeSha512,
        IProgress<(long Processed, long Total)>? progress = null,
        CancellationToken cancellationToken = default)
    {
        if (!includeSha256 && !includeSha512)
        {
            includeSha256 = true;
        }

        await using FileStream stream = new(
            path,
            FileMode.Open,
            FileAccess.Read,
            FileShare.Read,
            128 * 1024,
            FileOptions.Asynchronous | FileOptions.SequentialScan);
        using IncrementalHash? sha256 = includeSha256
            ? IncrementalHash.CreateHash(HashAlgorithmName.SHA256)
            : null;
        using IncrementalHash? sha512 = includeSha512
            ? IncrementalHash.CreateHash(HashAlgorithmName.SHA512)
            : null;
        long total = stream.Length;
        long processed = 0;
        byte[] buffer = new byte[128 * 1024];
        while (true)
        {
            int read = await stream.ReadAsync(buffer, cancellationToken).ConfigureAwait(false);
            if (read == 0)
            {
                break;
            }
            sha256?.AppendData(buffer, 0, read);
            sha512?.AppendData(buffer, 0, read);
            processed += read;
            progress?.Report((processed, total));
        }

        return (
            sha256 is null ? null : Convert.ToHexString(sha256.GetHashAndReset()),
            sha512 is null ? null : Convert.ToHexString(sha512.GetHashAndReset()));
    }
}
