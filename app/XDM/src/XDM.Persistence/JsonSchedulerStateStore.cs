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
        SchedulerRuntimeState? primary = await TryLoadAsync(_statePath, cancellationToken).ConfigureAwait(false);
        if (primary is not null)
        {
            return primary.Normalize();
        }

        SchedulerRuntimeState? backup = await TryLoadAsync(_statePath + ".bak", cancellationToken).ConfigureAwait(false);
        return (backup ?? SchedulerRuntimeState.Empty).Normalize();
    }

    public Task SaveAsync(SchedulerRuntimeState state, CancellationToken cancellationToken = default)
    {
        ArgumentNullException.ThrowIfNull(state);
        return AtomicFile.WriteAsync(
            _statePath,
            stream => JsonSerializer.SerializeAsync(
                stream,
                state.Normalize(),
                SerializerOptions,
                cancellationToken),
            createBackup: true,
            cancellationToken);
    }

    private static async Task<SchedulerRuntimeState?> TryLoadAsync(
        string path,
        CancellationToken cancellationToken)
    {
        if (!File.Exists(path))
        {
            return null;
        }

        try
        {
            await using FileStream stream = new(
                path,
                FileMode.Open,
                FileAccess.Read,
                FileShare.Read,
                16 * 1024,
                FileOptions.Asynchronous | FileOptions.SequentialScan);
            return await JsonSerializer
                .DeserializeAsync<SchedulerRuntimeState>(stream, SerializerOptions, cancellationToken)
                .ConfigureAwait(false);
        }
        catch (JsonException)
        {
            AtomicFile.Quarantine(path, "scheduler-json");
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

    private static string GetDefaultStatePath()
    {
        string baseDirectory = Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData);
        return Path.Combine(baseDirectory, "xdm-modern", "scheduler-state.json");
    }
}
