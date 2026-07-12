using XDM.Core.Downloads;

namespace XDM.Core.Persistence;

public sealed record DownloadListEntry(
    Uri Source,
    string? FileName = null,
    string? DestinationDirectory = null,
    string? QueueId = null,
    string? CategoryId = null,
    int ConnectionCount = 4,
    DownloadPriority Priority = DownloadPriority.Normal,
    Uri? SourcePage = null,
    IReadOnlyList<Uri>? Mirrors = null,
    string? ExpectedChecksumAlgorithm = null,
    string? ExpectedChecksum = null,
    long? ExpectedLength = null);

public sealed record DownloadListEnvelope(
    int SchemaVersion,
    DateTimeOffset ExportedAt,
    IReadOnlyList<DownloadListEntry> Downloads)
{
    public const int CurrentSchemaVersion = 1;
}

public sealed record DownloadListImportResult(
    IReadOnlyList<DownloadListEntry> Downloads,
    int IgnoredEntries,
    string SourceFormat);

public interface IDownloadListTransferService
{
    Task ExportAsync(
        string path,
        IReadOnlyCollection<DownloadSnapshot> downloads,
        CancellationToken cancellationToken = default);

    Task<DownloadListImportResult> ImportAsync(
        string path,
        CancellationToken cancellationToken = default);
}
