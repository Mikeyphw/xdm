namespace XDM.DownloadEngine;

public sealed class InsufficientDiskSpaceException : IOException
{
    public InsufficientDiskSpaceException(long requiredBytes, long availableBytes, string destinationPath)
        : base($"Not enough free space for '{destinationPath}'. Required: {requiredBytes} bytes; available: {availableBytes} bytes.")
    {
        RequiredBytes = requiredBytes;
        AvailableBytes = availableBytes;
        DestinationPath = destinationPath;
    }

    public long RequiredBytes { get; }

    public long AvailableBytes { get; }

    public string DestinationPath { get; }
}
