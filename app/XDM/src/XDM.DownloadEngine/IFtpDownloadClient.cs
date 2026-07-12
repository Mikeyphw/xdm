namespace XDM.DownloadEngine;

public interface IFtpDownloadClient
{
    Task<FtpDownloadResult> DownloadAsync(
        Uri source,
        string destinationPath,
        long resumeOffset,
        string? username,
        string? password,
        Func<long, long?, ValueTask> progress,
        CancellationToken cancellationToken = default);
}
