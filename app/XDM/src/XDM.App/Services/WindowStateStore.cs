using System.Text.Json;

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
        string stateDirectory = Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
            "xdm-modern");
        Directory.CreateDirectory(stateDirectory);
        _statePath = Path.Combine(stateDirectory, "window-state.json");
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
    }

    public async Task SaveAsync(WindowPlacementState state, CancellationToken cancellationToken = default)
    {
        ArgumentNullException.ThrowIfNull(state);
        string temporaryPath = _statePath + ".tmp";
        await using (FileStream stream = new(
            temporaryPath,
            FileMode.Create,
            FileAccess.Write,
            FileShare.None,
            bufferSize: 4096,
            FileOptions.Asynchronous | FileOptions.WriteThrough))
        {
            await JsonSerializer.SerializeAsync(
                stream,
                state,
                SerializerOptions,
                cancellationToken).ConfigureAwait(false);
            await stream.FlushAsync(cancellationToken).ConfigureAwait(false);
        }

        File.Move(temporaryPath, _statePath, overwrite: true);
    }

    public Task ResetAsync()
    {
        if (File.Exists(_statePath))
        {
            File.Delete(_statePath);
        }

        return Task.CompletedTask;
    }
}
