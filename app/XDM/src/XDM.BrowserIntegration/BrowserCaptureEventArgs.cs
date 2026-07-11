namespace XDM.BrowserIntegration;

public sealed class BrowserCaptureEventArgs(BrowserCaptureRequest request) : EventArgs
{
    public BrowserCaptureRequest Request { get; } = request;
}
