namespace XDM.Diagnostics;

public interface IRecoveryService
{
    bool SafeMode { get; }

    bool PreviousSessionWasUnclean { get; }

    string StateDirectory { get; }

    string SessionId { get; }

    ApplicationSessionState? PreviousSession { get; }

    void Initialize(StartupOptions options);

    void BeginShutdown(IReadOnlyList<string> activeDownloadIds);

    void RecordCheckpointFlush(
        bool succeeded,
        int attempted,
        int written,
        IReadOnlyList<string> failedDownloadIds);

    void MarkCleanShutdown();
}
