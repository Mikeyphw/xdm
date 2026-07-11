namespace XDM.BrowserIntegration;

public sealed record BrowserIntegrationStatus(
    bool IsListening,
    int Port,
    string ProtocolVersion,
    string AuthenticationToken,
    DateTimeOffset? StartedAt = null,
    DateTimeOffset? LastMessageAt = null,
    string? LastBrowser = null,
    string? LastCapturedUrl = null,
    string? LastError = null)
{
    public string Endpoint => $"http://127.0.0.1:{Port}";
}
