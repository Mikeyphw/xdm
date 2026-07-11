using System.Text.Json;

namespace XDM.Diagnostics;

public sealed class RecoveryService : IRecoveryService
{
    private readonly string _stateDirectory;
    private readonly string _sessionMarkerPath;
    private readonly string _windowStatePath;

    public RecoveryService()
        : this(GetDefaultStateDirectory())
    {
    }

    public RecoveryService(string stateDirectory)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(stateDirectory);
        _stateDirectory = stateDirectory;
        _sessionMarkerPath = Path.Combine(stateDirectory, "session.running.json");
        _windowStatePath = Path.Combine(stateDirectory, "window-state.json");
    }

    public bool SafeMode { get; private set; }

    public bool PreviousSessionWasUnclean { get; private set; }

    public string StateDirectory => _stateDirectory;

    public void Initialize(StartupOptions options)
    {
        ArgumentNullException.ThrowIfNull(options);
        Directory.CreateDirectory(_stateDirectory);
        SafeMode = options.SafeMode;
        PreviousSessionWasUnclean = File.Exists(_sessionMarkerPath);

        if (options.ResetWindowState && File.Exists(_windowStatePath))
        {
            File.Delete(_windowStatePath);
        }

        string payload = JsonSerializer.Serialize(new
        {
            processId = Environment.ProcessId,
            startedAt = DateTimeOffset.UtcNow,
            safeMode = SafeMode
        });
        File.WriteAllText(_sessionMarkerPath, payload);
    }

    public void MarkCleanShutdown()
    {
        if (File.Exists(_sessionMarkerPath))
        {
            File.Delete(_sessionMarkerPath);
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
