namespace XDM.BrowserIntegration;

public sealed record BrowserCaptureDecision(bool Accepted, string Reason, string? DownloadId = null)
{
    public static BrowserCaptureDecision Accept(string? downloadId = null) => new(true, "accepted", downloadId);
    public static BrowserCaptureDecision Reject(string reason) => new(false, reason);
}

public sealed record BrowserCaptureAcknowledgement(
    string? RequestId,
    bool Accepted,
    string Reason,
    string? DownloadId = null);
