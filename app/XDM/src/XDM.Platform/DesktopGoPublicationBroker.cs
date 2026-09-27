using System.Security.Cryptography;
using System.Text;
using System.Text.Json;

namespace XDM.Platform;

public sealed record DesktopPublicationCommit(
    string PublicationId,
    string DownloadId,
    long ArtifactGeneration,
    string IdempotencyKey,
    string StagingIdentity);

public sealed record DesktopPublicationRequest(
    DesktopPublicationCommit Commit,
    string StagingPath,
    string DestinationPath,
    string? CollisionPolicy = null);

public sealed record DesktopPublicationReceipt(
    string PublicationId,
    string ReceiptId,
    string Location);

public sealed class DesktopGoPublicationBroker : IDisposable
{
    private static readonly JsonSerializerOptions JsonOptions = new(JsonSerializerDefaults.Web) { WriteIndented = true };
    private readonly SemaphoreSlim _gate = new(1, 1);
    private readonly string _journalPath;
    private bool _disposed;

    public DesktopGoPublicationBroker()
        : this(Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
            "xdm-modern",
            "go-publication-journal-v1.json"))
    {
    }

    public DesktopGoPublicationBroker(string journalPath)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(journalPath);
        _journalPath = journalPath;
    }

    public async Task<DesktopPublicationReceipt> CommitAsync(
        DesktopPublicationRequest request,
        CancellationToken cancellationToken = default)
    {
        ArgumentNullException.ThrowIfNull(request);
        Validate(request);
        await _gate.WaitAsync(cancellationToken).ConfigureAwait(false);
        try
        {
            Journal journal = await LoadAsync(cancellationToken).ConfigureAwait(false);
            JournalEntry? existing = journal.Entries.FirstOrDefault(entry =>
                string.Equals(entry.IdempotencyKey, request.Commit.IdempotencyKey, StringComparison.Ordinal));
            if (existing is not null)
            {
                if (!string.Equals(existing.PublicationId, request.Commit.PublicationId, StringComparison.Ordinal))
                {
                    throw new InvalidDataException("Desktop publication idempotency key is already bound to a different publication.");
                }
                if (existing.Status == "committed")
                {
                    return existing.ToReceipt();
                }

                if (File.Exists(existing.Location) && !File.Exists(request.StagingPath))
                {
                    existing.Status = "committed";
                    await SaveAsync(journal, cancellationToken).ConfigureAwait(false);
                    return existing.ToReceipt();
                }
            }

            if (!File.Exists(request.StagingPath))
            {
                throw new FileNotFoundException("Desktop publication staging artifact is missing.", request.StagingPath);
            }

            string destination = existing?.Location ?? ResolveDestination(request.DestinationPath, request.CollisionPolicy);
            Directory.CreateDirectory(Path.GetDirectoryName(destination)
                ?? throw new InvalidOperationException("Desktop publication destination has no parent directory."));

            JournalEntry entry = existing ?? new JournalEntry
            {
                PublicationId = request.Commit.PublicationId,
                IdempotencyKey = request.Commit.IdempotencyKey,
                ReceiptId = CreateReceiptId(request.Commit.IdempotencyKey),
                Location = destination,
                Status = "pending"
            };
            if (existing is null)
            {
                journal.Entries.Add(entry);
            }
            else
            {
                entry.Location = destination;
            }
            await SaveAsync(journal, cancellationToken).ConfigureAwait(false);

            bool replace = string.Equals(request.CollisionPolicy, "replace", StringComparison.OrdinalIgnoreCase);
            File.Move(request.StagingPath, destination, replace);

            entry.Status = "committed";
            await SaveAsync(journal, cancellationToken).ConfigureAwait(false);
            return entry.ToReceipt();
        }
        finally
        {
            _gate.Release();
        }
    }

    private static void Validate(DesktopPublicationRequest request)
    {
        if (request.Commit is null
            || string.IsNullOrWhiteSpace(request.Commit.PublicationId)
            || string.IsNullOrWhiteSpace(request.Commit.DownloadId)
            || request.Commit.ArtifactGeneration <= 0
            || string.IsNullOrWhiteSpace(request.Commit.IdempotencyKey)
            || string.IsNullOrWhiteSpace(request.Commit.StagingIdentity)
            || string.IsNullOrWhiteSpace(request.StagingPath)
            || string.IsNullOrWhiteSpace(request.DestinationPath)
            || !Path.IsPathFullyQualified(request.StagingPath)
            || !Path.IsPathFullyQualified(request.DestinationPath))
        {
            throw new InvalidDataException("Invalid desktop publication request.");
        }
    }

    private static string ResolveDestination(string destination, string? policy)
    {
        if (!File.Exists(destination))
        {
            return destination;
        }
        if (string.Equals(policy, "replace", StringComparison.OrdinalIgnoreCase))
        {
            return destination;
        }
        if (!string.Equals(policy, "unique", StringComparison.OrdinalIgnoreCase))
        {
            throw new IOException($"Desktop publication destination already exists: {destination}");
        }

        string? directory = Path.GetDirectoryName(destination);
        string stem = Path.GetFileNameWithoutExtension(destination);
        string extension = Path.GetExtension(destination);
        for (int index = 1; index <= 9999; index++)
        {
            string candidate = Path.Combine(directory ?? string.Empty, $"{stem} ({index}){extension}");
            if (!File.Exists(candidate))
            {
                return candidate;
            }
        }
        throw new IOException("No collision-free desktop publication destination was available.");
    }

    private async Task<Journal> LoadAsync(CancellationToken cancellationToken)
    {
        if (!File.Exists(_journalPath))
        {
            return new Journal();
        }
        await using FileStream stream = File.OpenRead(_journalPath);
        return await JsonSerializer.DeserializeAsync<Journal>(stream, JsonOptions, cancellationToken).ConfigureAwait(false)
            ?? new Journal();
    }

    private async Task SaveAsync(Journal journal, CancellationToken cancellationToken)
    {
        string? directory = Path.GetDirectoryName(_journalPath);
        if (!string.IsNullOrWhiteSpace(directory))
        {
            Directory.CreateDirectory(directory);
        }
        string temp = _journalPath + ".tmp";
        await using (FileStream stream = new(temp, FileMode.Create, FileAccess.Write, FileShare.None, 16 * 1024, true))
        {
            await JsonSerializer.SerializeAsync(stream, journal, JsonOptions, cancellationToken).ConfigureAwait(false);
            await stream.FlushAsync(cancellationToken).ConfigureAwait(false);
        }
        File.Move(temp, _journalPath, true);
    }


    public void Dispose()
    {
        if (_disposed)
        {
            return;
        }
        _disposed = true;
        _gate.Dispose();
        GC.SuppressFinalize(this);
    }

    private static string CreateReceiptId(string idempotencyKey)
    {
        byte[] digest = SHA256.HashData(Encoding.UTF8.GetBytes(idempotencyKey));
        return "desktop-receipt:" + Convert.ToHexString(digest.AsSpan(0, 12)).ToLowerInvariant();
    }

    private sealed class Journal
    {
        public int SchemaVersion { get; set; } = 1;
        public List<JournalEntry> Entries { get; set; } = [];
    }

    private sealed class JournalEntry
    {
        public string PublicationId { get; set; } = string.Empty;
        public string IdempotencyKey { get; set; } = string.Empty;
        public string ReceiptId { get; set; } = string.Empty;
        public string Location { get; set; } = string.Empty;
        public string Status { get; set; } = "pending";

        public DesktopPublicationReceipt ToReceipt() => new(PublicationId, ReceiptId, Location);
    }
}
