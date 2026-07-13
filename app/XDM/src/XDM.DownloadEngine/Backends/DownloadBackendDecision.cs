using XDM.Core.Downloads;

namespace XDM.DownloadEngine.Backends;

public sealed record DownloadBackendDecision(
    DownloadBackendKind Backend,
    string Reason,
    bool IsFallback = false,
    bool CanStart = true);
