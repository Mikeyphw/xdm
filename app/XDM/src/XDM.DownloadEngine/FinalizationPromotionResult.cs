namespace XDM.DownloadEngine;

public sealed record FinalizationPromotionResult(
    string DestinationPath,
    bool UsedCrossFileSystemFallback,
    long BytesPromoted);
