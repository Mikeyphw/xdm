namespace XDM.BrowserIntegration;

public interface IBrowserIntegrationService : IDisposable
{
    event EventHandler<BrowserCaptureEventArgs>? CaptureReceived;

    event EventHandler<BrowserStatusChangedEventArgs>? StatusChanged;

    BrowserIntegrationStatus Current { get; }

    Task InitializeAsync(CancellationToken cancellationToken = default);

    Task StopAsync(CancellationToken cancellationToken = default);
}
