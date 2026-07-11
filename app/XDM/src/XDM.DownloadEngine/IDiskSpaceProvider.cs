namespace XDM.DownloadEngine;

public interface IDiskSpaceProvider
{
    long? GetAvailableBytes(string path);
}
