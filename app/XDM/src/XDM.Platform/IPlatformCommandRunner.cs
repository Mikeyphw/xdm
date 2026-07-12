namespace XDM.Platform;

public interface IPlatformCommandRunner
{
    Task<PlatformCommandResult> RunAsync(
        string executablePath,
        IReadOnlyList<string> arguments,
        TimeSpan timeout,
        CancellationToken cancellationToken = default);
}
