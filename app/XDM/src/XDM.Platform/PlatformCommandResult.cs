namespace XDM.Platform;

public sealed record PlatformCommandResult(
    int ExitCode,
    string StandardOutput,
    string StandardError,
    bool TimedOut);
