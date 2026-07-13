using System.Text.Json;

namespace XDM.App.Services;

public enum DownloadListDensity
{
    Comfortable,
    Compact
}

public sealed record DesktopProductivityState(
    DownloadListDensity DownloadDensity,
    double DownloadDetailsWidth,
    bool ShowSource,
    bool ShowQueue,
    bool ShowPriority,
    bool ShowTags,
    bool ShowSpeed,
    bool ShowRemaining,
    IReadOnlyList<string> MutedDownloadIds)
{
    public static DesktopProductivityState Default { get; } = new(
        DownloadListDensity.Comfortable,
        360,
        true,
        true,
        true,
        true,
        true,
        true,
        []);

    public DesktopProductivityState Normalize()
        => this with
        {
            DownloadDensity = Enum.IsDefined(DownloadDensity)
                ? DownloadDensity
                : DownloadListDensity.Comfortable,
            DownloadDetailsWidth = Math.Clamp(DownloadDetailsWidth, 260, 760),
            MutedDownloadIds = MutedDownloadIds?
                .Where(static id => !string.IsNullOrWhiteSpace(id))
                .Select(static id => id.Trim())
                .Distinct(StringComparer.Ordinal)
                .Take(2048)
                .ToArray() ?? []
        };
}

public sealed class DesktopProductivityStateStore : IDisposable
{
    private static readonly JsonSerializerOptions SerializerOptions = new(JsonSerializerDefaults.Web)
    {
        WriteIndented = true
    };

    private readonly string _statePath;
    private readonly SemaphoreSlim _gate = new(1, 1);

    public DesktopProductivityStateStore()
        : this(Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
            "xdm-modern",
            "desktop-productivity.json"))
    {
    }

    internal DesktopProductivityStateStore(string statePath)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(statePath);
        _statePath = statePath;
    }

    public async Task<DesktopProductivityState> LoadAsync(CancellationToken cancellationToken = default)
    {
        await _gate.WaitAsync(cancellationToken).ConfigureAwait(false);
        try
        {
            if (!File.Exists(_statePath))
            {
                return DesktopProductivityState.Default;
            }

            await using FileStream stream = new(
                _statePath,
                FileMode.Open,
                FileAccess.Read,
                FileShare.Read,
                8 * 1024,
                FileOptions.Asynchronous | FileOptions.SequentialScan);
            DesktopProductivityState? state = await JsonSerializer
                .DeserializeAsync<DesktopProductivityState>(stream, SerializerOptions, cancellationToken)
                .ConfigureAwait(false);
            return (state ?? DesktopProductivityState.Default).Normalize();
        }
        catch (JsonException)
        {
            return DesktopProductivityState.Default;
        }
        catch (IOException)
        {
            return DesktopProductivityState.Default;
        }
        catch (UnauthorizedAccessException)
        {
            return DesktopProductivityState.Default;
        }
        finally
        {
            _gate.Release();
        }
    }

    public async Task SaveAsync(
        DesktopProductivityState state,
        CancellationToken cancellationToken = default)
    {
        ArgumentNullException.ThrowIfNull(state);
        await _gate.WaitAsync(cancellationToken).ConfigureAwait(false);
        try
        {
            string? directory = Path.GetDirectoryName(_statePath);
            if (!string.IsNullOrWhiteSpace(directory))
            {
                Directory.CreateDirectory(directory);
            }

            string temporaryPath = _statePath + ".tmp";
            await using (FileStream stream = new(
                temporaryPath,
                FileMode.Create,
                FileAccess.Write,
                FileShare.None,
                8 * 1024,
                FileOptions.Asynchronous | FileOptions.WriteThrough))
            {
                await JsonSerializer
                    .SerializeAsync(stream, state.Normalize(), SerializerOptions, cancellationToken)
                    .ConfigureAwait(false);
                await stream.FlushAsync(cancellationToken).ConfigureAwait(false);
            }

            File.Move(temporaryPath, _statePath, overwrite: true);
        }
        finally
        {
            _gate.Release();
        }
    }

    public void Dispose()
    {
        _gate.Dispose();
        GC.SuppressFinalize(this);
    }
}
