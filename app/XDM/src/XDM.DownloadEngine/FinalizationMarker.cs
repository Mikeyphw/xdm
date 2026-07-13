namespace XDM.DownloadEngine;

public sealed record FinalizationMarker(
    int Version,
    long ExpectedLength,
    string? ChecksumAlgorithm,
    string? Checksum,
    DateTimeOffset CreatedAt,
    bool LocalIntegrityRecordOnly = false,
    FinalizationStage Stage = FinalizationStage.Prepared,
    string? SourcePath = null,
    string? StagingPath = null,
    DateTimeOffset? UpdatedAt = null)
{
    public const int CurrentVersion = 2;
}
