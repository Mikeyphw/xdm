namespace XDM.Media;

public interface IExternalToolRunner
{
    Task<ExternalToolResult> RunAsync(
        string executablePath,
        IReadOnlyList<string> arguments,
        TimeSpan timeout,
        int maximumOutputBytes,
        CancellationToken cancellationToken = default);
}
