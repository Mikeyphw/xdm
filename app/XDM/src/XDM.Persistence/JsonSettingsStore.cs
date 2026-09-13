using System.Text.Json;
using XDM.Core.Persistence;
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
            return await TryLoadBackupAsync(cancellationToken).ConfigureAwait(false);
        }

        try
        {
            return await LoadFileAsync(_settingsPath, cancellationToken).ConfigureAwait(false);
        }
        catch (UnsupportedSettingsSchemaException)
        {
            AtomicFile.Quarantine(_settingsPath, "incompatible");
            throw;
        }
        catch (Exception exception) when (exception is JsonException or IOException or UnauthorizedAccessException)
        {
            AtomicFile.Quarantine(_settingsPath, "corrupt");
            return await TryLoadBackupAsync(cancellationToken).ConfigureAwait(false);
        }
    }

    public Task SaveAsync(ApplicationSettings settings, CancellationToken cancellationToken = default)
    {
        ArgumentNullException.ThrowIfNull(settings);
        ApplicationSettings normalized = settings.Normalize();
        return AtomicFile.WriteAsync(
            _settingsPath,
            stream => JsonSerializer.SerializeAsync(
                stream,
                normalized,
                SerializerOptions,
                cancellationToken),
            createBackup: true,
            cancellationToken);
    }

    private async Task<ApplicationSettings?> TryLoadBackupAsync(CancellationToken cancellationToken)
    {
        string backupPath = $"{_settingsPath}.bak";
        if (!File.Exists(backupPath))
        {
            return null;
        }

        try
        {
            return await LoadFileAsync(backupPath, cancellationToken).ConfigureAwait(false);
        }
        catch (UnsupportedSettingsSchemaException)
        {
            AtomicFile.Quarantine(backupPath, "incompatible");
            return null;
        }
        catch (Exception exception) when (exception is JsonException or IOException or UnauthorizedAccessException)
        {
            AtomicFile.Quarantine(backupPath, "corrupt");
            return null;
        }
    }

    private static async Task<ApplicationSettings?> LoadFileAsync(
        string path,
        CancellationToken cancellationToken)
    {
        await using FileStream stream = new(
            path,
            FileMode.Open,
            FileAccess.Read,
            FileShare.Read,
            16 * 1024,
            FileOptions.Asynchronous | FileOptions.SequentialScan);

        ApplicationSettings? settings = await JsonSerializer
            .DeserializeAsync<ApplicationSettings>(stream, SerializerOptions, cancellationToken)
            .ConfigureAwait(false);
        if (settings is null)
        {
            return null;
        }

        if (settings.SchemaVersion > ApplicationSettings.CurrentSchemaVersion)
        {
            throw new UnsupportedSettingsSchemaException(
                $"Settings schema {settings.SchemaVersion} is newer than supported schema {ApplicationSettings.CurrentSchemaVersion}. The incompatible file was quarantined for recovery.");
        }

        return settings.Normalize();
    }

    private sealed class UnsupportedSettingsSchemaException(string message) : InvalidDataException(message);

    private static string GetDefaultSettingsPath()
    {
        string baseDirectory = Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData);
        return Path.Combine(baseDirectory, "xdm-modern", "settings.json");
    }
}
