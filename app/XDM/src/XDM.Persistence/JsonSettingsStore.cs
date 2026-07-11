using System.Text.Json;
using XDM.Core.Settings;

namespace XDM.Persistence;

public sealed class JsonSettingsStore : ISettingsStore
{
    private static readonly JsonSerializerOptions SerializerOptions = new(JsonSerializerDefaults.Web)
    {
        WriteIndented = true
    };

    private readonly string _settingsPath;

    public JsonSettingsStore()
        : this(GetDefaultSettingsPath())
    {
    }

    public JsonSettingsStore(string settingsPath)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(settingsPath);
        _settingsPath = settingsPath;
    }

    public async Task<ApplicationSettings?> LoadAsync(CancellationToken cancellationToken = default)
    {
        if (!File.Exists(_settingsPath))
        {
            return null;
        }

        try
        {
            await using FileStream stream = new(
                _settingsPath,
                FileMode.Open,
                FileAccess.Read,
                FileShare.Read,
                16 * 1024,
                FileOptions.Asynchronous | FileOptions.SequentialScan);

            return await JsonSerializer
                .DeserializeAsync<ApplicationSettings>(stream, SerializerOptions, cancellationToken)
                .ConfigureAwait(false);
        }
        catch (JsonException)
        {
            QuarantineCorruptSettings();
            return null;
        }
    }

    public async Task SaveAsync(ApplicationSettings settings, CancellationToken cancellationToken = default)
    {
        ArgumentNullException.ThrowIfNull(settings);

        string? directory = Path.GetDirectoryName(_settingsPath);
        if (!string.IsNullOrWhiteSpace(directory))
        {
            Directory.CreateDirectory(directory);
        }

        string temporaryPath = $"{_settingsPath}.tmp";
        string backupPath = $"{_settingsPath}.bak";
        await using (FileStream stream = new(
            temporaryPath,
            FileMode.Create,
            FileAccess.Write,
            FileShare.None,
            16 * 1024,
            FileOptions.Asynchronous | FileOptions.WriteThrough))
        {
            await JsonSerializer
                .SerializeAsync(stream, settings.Normalize(), SerializerOptions, cancellationToken)
                .ConfigureAwait(false);
            await stream.FlushAsync(cancellationToken).ConfigureAwait(false);
        }

        if (File.Exists(_settingsPath))
        {
            File.Copy(_settingsPath, backupPath, overwrite: true);
        }

        File.Move(temporaryPath, _settingsPath, overwrite: true);
    }

    private static string GetDefaultSettingsPath()
    {
        string baseDirectory = Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData);
        return Path.Combine(baseDirectory, "xdm-modern", "settings.json");
    }

    private void QuarantineCorruptSettings()
    {
        string corruptPath = $"{_settingsPath}.corrupt-{DateTimeOffset.UtcNow:yyyyMMddHHmmss}";
        try
        {
            File.Move(_settingsPath, corruptPath, overwrite: true);
        }
        catch (IOException)
        {
        }
        catch (UnauthorizedAccessException)
        {
        }
    }
}
