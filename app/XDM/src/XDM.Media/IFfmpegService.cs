namespace XDM.Media;

public interface IFfmpegService
{
    Task<ExternalToolHealth> GetHealthAsync(CancellationToken cancellationToken = default);

    async Task<FfmpegCapabilities> GetCapabilitiesAsync(CancellationToken cancellationToken = default)
    {
        ExternalToolHealth health = await GetHealthAsync(cancellationToken).ConfigureAwait(false);
        return new FfmpegCapabilities(health, false, false, false, false, false, false);
    }

    Task MuxAsync(
        IReadOnlyList<string> inputPaths,
        string destinationPath,
        CancellationToken cancellationToken = default);
}
