namespace XDM.Core.Settings;

public interface ISettingsStore
{
    Task<ApplicationSettings?> LoadAsync(CancellationToken cancellationToken = default);

    Task SaveAsync(ApplicationSettings settings, CancellationToken cancellationToken = default);
}
