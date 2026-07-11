namespace XDM.BrowserIntegration;

public sealed class BrowserStatusChangedEventArgs(BrowserIntegrationStatus status) : EventArgs
{
    public BrowserIntegrationStatus Status { get; } = status;
}
