using System.Text.Json;
using XDM.Core.Persistence;

namespace XDM.App.Services;

public sealed record WindowPlacementState(
    int X,
    int Y,
    double Width,
    double Height,
    bool IsMaximized);

public sealed class WindowStateStore
{
    private static readonly JsonSerializerOptions SerializerOptions = new(JsonSerializerDefaults.Web)
    {
        WriteIndented = true
    };

    private readonly string _statePath;

    public WindowStateStore()
    {
        _statePath = Path.Combine(GetStateDirectory(), "window-state.json");
    }

    public async Task<WindowPlacementState?> LoadAsync(CancellationToken cancellationToken = default)
    {
        if (!File.Exists(_statePath))
        {
            return null;
        }

        try
        {
            await using FileStream stream = File.OpenRead(_statePath);
            return await JsonSerializer.DeserializeAsync<WindowPlacementState>(
                stream,
                SerializerOptions,
                cancellationToken).ConfigureAwait(false);
        }
        catch (JsonException)
        {
            return null;
        }
        catch (IOException)
        {
            return null;
        }
        catch (UnauthorizedAccessException)
        {
            return null;
        }
    }

    public Task SaveAsync(WindowPlacementState state, CancellationToken cancellationToken = default)
    {
        ArgumentNullException.ThrowIfNull(state);
        return AtomicFile.WriteAsync(
            _statePath,
            stream => JsonSerializer.SerializeAsync(
                stream,
                state,
                SerializerOptions,
                cancellationToken),
            createBackup: true,
            cancellationToken);
    }

    public async Task<bool> SaveBestEffortAsync(WindowPlacementState state, CancellationToken cancellationToken = default)
    {
        try
        {
            await SaveAsync(state, cancellationToken).ConfigureAwait(false);
            return true;
        }
        catch (IOException)
        {
            return false;
        }
        catch (UnauthorizedAccessException)
        {
            return false;
        }
    }

    private static string GetStateDirectory()
    {
        string? xdgStateHome = Environment.GetEnvironmentVariable("XDG_STATE_HOME");
        if (!string.IsNullOrWhiteSpace(xdgStateHome))
        {
            return Path.Combine(xdgStateHome, "xdm");
        }

        string localData = Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData);
        return Path.Combine(localData, "XDM", "State");
    }

    public Task ResetAsync()
    {
        try
        {
            if (File.Exists(_statePath))
            {
                File.Delete(_statePath);
            }
        }
        catch (IOException)
        {
        }
        catch (UnauthorizedAccessException)
        {
        }

        return Task.CompletedTask;
    }
}
