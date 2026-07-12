namespace XDM.DownloadEngine;

public sealed record DownloadRepairResult(
    string DownloadId,
    bool Restarted,
    string? PreservedCorruptPath,
    string Message);
