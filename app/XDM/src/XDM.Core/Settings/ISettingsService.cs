namespace XDM.Core.Settings;

public interface ISettingsService
{
    ApplicationSettings Current { get; }

    event EventHandler<ApplicationSettings>? Changed;

    Task InitializeAsync(CancellationToken cancellationToken = default);

    Task UpdateAsync(ApplicationSettings settings, CancellationToken cancellationToken = default);
}
