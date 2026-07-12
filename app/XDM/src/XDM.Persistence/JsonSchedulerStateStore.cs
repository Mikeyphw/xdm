using System.Text.Json;
using XDM.Core.Scheduling;

namespace XDM.Persistence;

public sealed class JsonSchedulerStateStore : ISchedulerStateStore
{
    private static readonly JsonSerializerOptions SerializerOptions = new(JsonSerializerDefaults.Web)
    {
        WriteIndented = true
    };

    private readonly string _statePath;

    public JsonSchedulerStateStore()
        : this(GetDefaultStatePath())
    {
    }

    public JsonSchedulerStateStore(string statePath)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(statePath);
        _statePath = statePath;
    }

    public async Task<SchedulerRuntimeState> LoadAsync(CancellationToken cancellationToken = default)
    {
        if (!File.Exists(_statePath))
        {
            return SchedulerRuntimeState.Empty;
        }

        try
        {
            await using FileStream stream = new(
                _statePath,
                FileMode.Open,
                FileAccess.Read,
                FileShare.Read,
                16 * 1024,
                FileOptions.Asynchronous | FileOptions.SequentialScan);
            SchedulerRuntimeState? state = await JsonSerializer
                .DeserializeAsync<SchedulerRuntimeState>(stream, SerializerOptions, cancellationToken)
                .ConfigureAwait(false);
            return state ?? SchedulerRuntimeState.Empty;
        }
        catch (JsonException)
        {
            return SchedulerRuntimeState.Empty;
        }
        catch (IOException)
        {
            return SchedulerRuntimeState.Empty;
        }
    }

    public async Task SaveAsync(SchedulerRuntimeState state, CancellationToken cancellationToken = default)
    {
        ArgumentNullException.ThrowIfNull(state);
        string? directory = Path.GetDirectoryName(_statePath);
        if (!string.IsNullOrWhiteSpace(directory))
        {
            Directory.CreateDirectory(directory);
        }

        string temporaryPath = $"{_statePath}.tmp";
        await using (FileStream stream = new(
            temporaryPath,
            FileMode.Create,
            FileAccess.Write,
            FileShare.None,
            16 * 1024,
            FileOptions.Asynchronous | FileOptions.WriteThrough))
        {
            await JsonSerializer
                .SerializeAsync(stream, state, SerializerOptions, cancellationToken)
                .ConfigureAwait(false);
            await stream.FlushAsync(cancellationToken).ConfigureAwait(false);
        }

        File.Move(temporaryPath, _statePath, overwrite: true);
    }

    private static string GetDefaultStatePath()
    {
        string baseDirectory = Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData);
        return Path.Combine(baseDirectory, "xdm-modern", "scheduler-state.json");
    }
}
