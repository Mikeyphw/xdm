using System.Text;
using System.Text.Json;
using XDM.Core.Downloads;
using XDM.Core.Persistence;

namespace XDM.Persistence;

public sealed class DownloadListTransferService : IDownloadListTransferService
{
    private const long MaximumImportBytes = 8 * 1024 * 1024;
    private const int MaximumEntries = 50_000;
    private static readonly string[] LineSeparators = ["\r\n", "\n"];
    private static readonly HashSet<char> InvalidFileNameCharacters = new(Path.GetInvalidFileNameChars());
    private static readonly JsonSerializerOptions SerializerOptions = new(JsonSerializerDefaults.Web)
    {
        WriteIndented = true
    };

    public async Task ExportAsync(
        string path,
        IReadOnlyCollection<DownloadSnapshot> downloads,
        CancellationToken cancellationToken = default)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(path);
        ArgumentNullException.ThrowIfNull(downloads);
        if (downloads.Count > MaximumEntries)
        {
            throw new InvalidDataException($"A download-list export is limited to {MaximumEntries:N0} entries.");
        }

        DownloadListEntry[] entries = downloads
            .OrderByDescending(static item => item.UpdatedAt)
            .Select(static item => new DownloadListEntry(
                item.Source,
                item.FileName,
                Path.GetDirectoryName(item.DestinationPath),
                item.QueueId,
                item.CategoryId,
                item.ConnectionCount,
                item.Priority,
                item.SourcePage))
            .ToArray();
        DownloadListEnvelope envelope = new(
            DownloadListEnvelope.CurrentSchemaVersion,
            DateTimeOffset.UtcNow,
            entries);

        string fullPath = Path.GetFullPath(path);
        string? directory = Path.GetDirectoryName(fullPath);
        if (!string.IsNullOrWhiteSpace(directory))
        {
            Directory.CreateDirectory(directory);
        }

        string temporaryPath = $"{fullPath}.tmp";
        try
        {
            await using (FileStream stream = new(
                temporaryPath,
                FileMode.Create,
                FileAccess.Write,
                FileShare.None,
                16 * 1024,
                FileOptions.Asynchronous | FileOptions.WriteThrough))
            {
                await JsonSerializer.SerializeAsync(stream, envelope, SerializerOptions, cancellationToken)
                    .ConfigureAwait(false);
                await stream.FlushAsync(cancellationToken).ConfigureAwait(false);
                stream.Flush(flushToDisk: true);
            }

            File.Move(temporaryPath, fullPath, overwrite: true);
        }
        finally
        {
            if (File.Exists(temporaryPath))
            {
                File.Delete(temporaryPath);
            }
        }
    }

    public async Task<DownloadListImportResult> ImportAsync(
        string path,
        CancellationToken cancellationToken = default)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(path);
        FileInfo file = new(Path.GetFullPath(path));
        if (!file.Exists)
        {
            throw new FileNotFoundException("The download-list file does not exist.", file.FullName);
        }

        if (file.Length > MaximumImportBytes)
        {
            throw new InvalidDataException("The download-list file exceeds the 8 MiB safety limit.");
        }

        string content = await File.ReadAllTextAsync(file.FullName, Encoding.UTF8, cancellationToken)
            .ConfigureAwait(false);
        string trimmed = content.TrimStart();
        return trimmed.StartsWith('{')
            ? ImportJson(content)
            : ImportText(content);
    }

    private static DownloadListImportResult ImportJson(string content)
    {
        DownloadListEnvelope? envelope = JsonSerializer.Deserialize<DownloadListEnvelope>(content, SerializerOptions);
        if (envelope is null || envelope.SchemaVersion != DownloadListEnvelope.CurrentSchemaVersion)
        {
            throw new InvalidDataException("The download-list schema version is unsupported.");
        }

        return NormalizeEntries(envelope.Downloads, "XDM download-list JSON");
    }

    private static DownloadListImportResult ImportText(string content)
    {
        string[] lines = content.Split(
            LineSeparators,
            StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries);
        List<DownloadListEntry> entries = [];
        int ignored = 0;
        foreach (string line in lines)
        {
            if (line.StartsWith('#'))
            {
                continue;
            }

            if (Uri.TryCreate(line, UriKind.Absolute, out Uri? uri))
            {
                entries.Add(new DownloadListEntry(uri));
            }
            else
            {
                ignored++;
            }
        }

        DownloadListImportResult normalized = NormalizeEntries(entries, "plain URL list");
        return normalized with { IgnoredEntries = normalized.IgnoredEntries + ignored };
    }

    private static DownloadListImportResult NormalizeEntries(
        IReadOnlyList<DownloadListEntry>? entries,
        string sourceFormat)
    {
        if (entries is null)
        {
            throw new InvalidDataException("The download-list contains no entries.");
        }

        List<DownloadListEntry> normalized = new(Math.Min(entries.Count, MaximumEntries));
        int ignored = 0;
        int entryLimit = Math.Min(entries.Count, MaximumEntries);
        for (int index = 0; index < entryLimit; index++)
        {
            DownloadListEntry entry = entries[index];
            if (!IsSafeHttpUri(entry.Source))
            {
                ignored++;
                continue;
            }

            Uri? sourcePage = IsSafeHttpUri(entry.SourcePage) ? entry.SourcePage : null;
            normalized.Add(entry with
            {
                FileName = NormalizeFileName(entry.FileName),
                DestinationDirectory = NormalizeDirectory(entry.DestinationDirectory),
                QueueId = NormalizeIdentifier(entry.QueueId),
                CategoryId = NormalizeIdentifier(entry.CategoryId),
                ConnectionCount = Math.Clamp(entry.ConnectionCount, 1, 32),
                SourcePage = sourcePage
            });
        }

        ignored += Math.Max(0, entries.Count - MaximumEntries);
        return new DownloadListImportResult(normalized, ignored, sourceFormat);
    }

    private static bool IsSafeHttpUri(Uri? uri)
        => uri is { IsAbsoluteUri: true }
            && uri.Scheme is "http" or "https";

    private static string? NormalizeFileName(string? value)
    {
        if (string.IsNullOrWhiteSpace(value))
        {
            return null;
        }

        string normalized = new(value.Trim().Select(character =>
            InvalidFileNameCharacters.Contains(character) ? '_' : character).ToArray());
        return normalized.Length == 0 ? null : normalized[..Math.Min(normalized.Length, 255)];
    }

    private static string? NormalizeDirectory(string? value)
    {
        if (string.IsNullOrWhiteSpace(value))
        {
            return null;
        }

        string normalized = value.Trim();
        try
        {
            return Path.IsPathFullyQualified(normalized)
                ? Path.GetFullPath(normalized)
                : null;
        }
        catch (ArgumentException)
        {
            return null;
        }
        catch (NotSupportedException)
        {
            return null;
        }
        catch (PathTooLongException)
        {
            return null;
        }
    }

    private static string? NormalizeIdentifier(string? value)
    {
        if (string.IsNullOrWhiteSpace(value))
        {
            return null;
        }

        string normalized = value.Trim();
        return normalized[..Math.Min(normalized.Length, 128)];
    }
}
