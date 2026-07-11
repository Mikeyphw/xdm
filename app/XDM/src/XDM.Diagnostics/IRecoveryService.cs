namespace XDM.Diagnostics;

public interface IRecoveryService
{
    bool SafeMode { get; }

    bool PreviousSessionWasUnclean { get; }

    string StateDirectory { get; }

    void Initialize(StartupOptions options);

    void MarkCleanShutdown();
}
