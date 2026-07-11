namespace XDM.DownloadEngine;

public sealed record DownloadSegment(int Index, long Start, long End)
{
    public long Length => checked(End - Start + 1);
}
