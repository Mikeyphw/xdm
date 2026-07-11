namespace XDM.BrowserIntegration;

public sealed class BrowserCaptureEventArgs : EventArgs
{
    private readonly TaskCompletionSource<BrowserCaptureDecision> _completion = new(
        TaskCreationOptions.RunContinuationsAsynchronously);

    public BrowserCaptureEventArgs(BrowserCaptureRequest request)
    {
        Request = request;
    }

    public BrowserCaptureRequest Request { get; }

    public bool Accept(string? downloadId = null)
        => _completion.TrySetResult(BrowserCaptureDecision.Accept(downloadId));

    public bool Reject(string reason)
        => _completion.TrySetResult(BrowserCaptureDecision.Reject(reason));

    public Task<BrowserCaptureDecision> WaitForDecisionAsync(
        TimeSpan timeout,
        CancellationToken cancellationToken)
        => _completion.Task.WaitAsync(timeout, cancellationToken);
}
