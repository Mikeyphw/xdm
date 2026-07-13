namespace XDM.DownloadEngine;

public sealed record DownloadRepairResult(
    string DownloadId,
    bool Restarted,
    string? PreservedCorruptPath,
    string Message,
    long BytesScanned = 0,
    long BytesDownloaded = 0,
    long BytesRepaired = 0,
    int RepairedRangeCount = 0,
    bool Finalized = false,
    bool ChecksumMatched = false);
