namespace XDM.Media;

public interface IMediaProbeService
{
    Task<MediaProbeResult> ProbeAsync(Uri source, CancellationToken cancellationToken = default);
}
