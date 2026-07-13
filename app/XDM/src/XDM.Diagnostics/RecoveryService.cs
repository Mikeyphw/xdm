using System.Text;
using System.Text.Json;

namespace XDM.Diagnostics;

public sealed class RecoveryService : IRecoveryService
{
    private static readonly JsonSerializerOptions SerializerOptions = new(JsonSerializerDefaults.Web)
    {
        WriteIndented = true
    };

    private readonly object _sync = new();
    private readonly string _stateDirectory;
    private readonly string _sessionMarkerPath;
    private readonly string _lastSessionPath;
    private readonly string _previousUncleanSessionPath;
    private readonly string _windowStatePath;
    private ApplicationSessionState? _currentSession;

    public RecoveryService()
        : this(GetDefaultStateDirectory())
    {
    }

    public RecoveryService(string stateDirectory)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(stateDirectory);
        _stateDirectory = stateDirectory;
        _sessionMarkerPath = Path.Combine(stateDirectory, "session.running.json");
        _lastSessionPath = Path.Combine(stateDirectory, "session.last.json");
        _previousUncleanSessionPath = Path.Combine(stateDirectory, "session.previous-unclean.json");
        _windowStatePath = Path.Combine(stateDirectory, "window-state.json");
    }

    public bool SafeMode { get; private set; }

    public bool PreviousSessionWasUnclean { get; private set; }

    public string StateDirectory => _stateDirectory;

    public string SessionId => _currentSession?.SessionId ?? string.Empty;

    public ApplicationSessionState? PreviousSession { get; private set; }

    public void Initialize(StartupOptions options)
    {
        ArgumentNullException.ThrowIfNull(options);
        Directory.CreateDirectory(_stateDirectory);
        SafeMode = options.SafeMode;
        PreviousSession = LoadSession(_sessionMarkerPath);
        PreviousSessionWasUnclean = File.Exists(_sessionMarkerPath)
            && PreviousSession?.IsClean != true;
        if (PreviousSessionWasUnclean)
        {
            File.Copy(_sessionMarkerPath, _previousUncleanSessionPath, overwrite: true);
        }

        if (options.ResetWindowState && File.Exists(_windowStatePath))
        {
            File.Delete(_windowStatePath);
        }

        _currentSession = new ApplicationSessionState(
            ApplicationSessionState.CurrentVersion,
            Guid.NewGuid().ToString("N"),
            Environment.ProcessId,
            DateTimeOffset.UtcNow,
            SafeMode,
            null,
            null,
            null,
            0,
            0,
            Array.Empty<string>(),
            Array.Empty<string>(),
            null);
        SaveCurrentSession();
    }

    public void BeginShutdown(IReadOnlyList<string> activeDownloadIds)
    {
        ArgumentNullException.ThrowIfNull(activeDownloadIds);
        lock (_sync)
        {
            EnsureInitialized();
            _currentSession = _currentSession! with
            {
                ShutdownStartedAt = DateTimeOffset.UtcNow,
                ActiveDownloadIds = activeDownloadIds
                    .Where(static id => !string.IsNullOrWhiteSpace(id))
                    .Distinct(StringComparer.Ordinal)
                    .OrderBy(static id => id, StringComparer.Ordinal)
                    .ToArray()
            };
            SaveCurrentSessionLocked();
        }
    }

    public void RecordCheckpointFlush(
        bool succeeded,
        int attempted,
        int written,
        IReadOnlyList<string> failedDownloadIds)
    {
        ArgumentNullException.ThrowIfNull(failedDownloadIds);
        ArgumentOutOfRangeException.ThrowIfNegative(attempted);
        ArgumentOutOfRangeException.ThrowIfNegative(written);
        lock (_sync)
        {
            EnsureInitialized();
            _currentSession = _currentSession! with
            {
                CheckpointFlushCompletedAt = DateTimeOffset.UtcNow,
                CheckpointFlushSucceeded = succeeded,
                CheckpointsAttempted = attempted,
                CheckpointsWritten = written,
                FailedCheckpointDownloadIds = failedDownloadIds
                    .Where(static id => !string.IsNullOrWhiteSpace(id))
                    .Distinct(StringComparer.Ordinal)
                    .OrderBy(static id => id, StringComparer.Ordinal)
                    .ToArray()
            };
            SaveCurrentSessionLocked();
        }
    }

    public void MarkCleanShutdown()
    {
        lock (_sync)
        {
            EnsureInitialized();
            if (_currentSession!.CheckpointFlushSucceeded != true)
            {
                throw new InvalidOperationException(
                    "A clean shutdown cannot be recorded before the transfer checkpoint flush succeeds.");
            }
            _currentSession = _currentSession with { CleanShutdownAt = DateTimeOffset.UtcNow };
            SaveCurrentSessionLocked();
            File.Copy(_sessionMarkerPath, _lastSessionPath, overwrite: true);
            File.Delete(_sessionMarkerPath);
        }
    }

    private void SaveCurrentSession()
    {
        lock (_sync)
        {
            SaveCurrentSessionLocked();
        }
    }

    private void SaveCurrentSessionLocked()
    {
        EnsureInitialized();
        string temporaryPath = $"{_sessionMarkerPath}.tmp";
        byte[] payload = Encoding.UTF8.GetBytes(JsonSerializer.Serialize(_currentSession, SerializerOptions));
        using (FileStream stream = new(
            temporaryPath,
            FileMode.Create,
            FileAccess.Write,
            FileShare.None,
            4096,
            FileOptions.WriteThrough))
        {
            stream.Write(payload);
            stream.Flush(flushToDisk: true);
        }
        File.Move(temporaryPath, _sessionMarkerPath, overwrite: true);
    }

    private static ApplicationSessionState? LoadSession(string path)
    {
        if (!File.Exists(path))
        {
            return null;
        }
        try
        {
            string payload = File.ReadAllText(path);
            ApplicationSessionState? session = JsonSerializer.Deserialize<ApplicationSessionState>(payload, SerializerOptions);
            if (session is null
                || session.Version != ApplicationSessionState.CurrentVersion
                || string.IsNullOrWhiteSpace(session.SessionId)
                || session.ActiveDownloadIds is null
                || session.FailedCheckpointDownloadIds is null)
            {
                return null;
            }
            return session;
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

    private void EnsureInitialized()
    {
        if (_currentSession is null)
        {
            throw new InvalidOperationException("RecoveryService has not been initialized.");
        }
    }

    private static string GetDefaultStateDirectory()
    {
        string? xdgStateHome = Environment.GetEnvironmentVariable("XDG_STATE_HOME");
        if (!string.IsNullOrWhiteSpace(xdgStateHome))
        {
            return Path.Combine(xdgStateHome, "xdm");
        }

        string localData = Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData);
        return Path.Combine(localData, "XDM", "State");
    }
}
