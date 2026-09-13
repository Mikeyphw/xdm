namespace XDM.Core.Settings;

public interface ISettingsService
{
    ApplicationSettings Current { get; }

    bool IsOperational => true;

    string? LoadFailureMessage => null;

    event EventHandler<ApplicationSettings>? Changed;

    Task InitializeAsync(CancellationToken cancellationToken = default);

    Task UpdateAsync(ApplicationSettings settings, CancellationToken cancellationToken = default);
}
