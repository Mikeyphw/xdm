namespace XDM.Media;

public interface IMediaDownloadService
{
    Task<MediaDownloadResult> DownloadAsync(
        MediaDownloadRequest request,
        IProgress<MediaDownloadProgress>? progress = null,
        CancellationToken cancellationToken = default);
}
