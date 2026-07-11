namespace XDM.DownloadEngine;

public sealed class DownloadIntegrityException : IOException
{
    public DownloadIntegrityException(string message)
        : base(message)
    {
    }
}
