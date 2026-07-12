namespace XDM.Media;

public interface IFfmpegService
{
    Task<ExternalToolHealth> GetHealthAsync(CancellationToken cancellationToken = default);

    Task MuxAsync(
        IReadOnlyList<string> inputPaths,
        string destinationPath,
        CancellationToken cancellationToken = default);
}
