using System.Text.Json;
using XDM.Core.Scheduling;
using XDM.Core.Persistence;

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

    public Task SaveAsync(SchedulerRuntimeState state, CancellationToken cancellationToken = default)
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

    private static string GetDefaultStatePath()
    {
        string baseDirectory = Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData);
        return Path.Combine(baseDirectory, "xdm-modern", "scheduler-state.json");
    }
}
