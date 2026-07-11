namespace XDM.DownloadEngine;

public sealed record SegmentedDownloadPlan(long TotalLength, IReadOnlyList<DownloadSegment> Segments)
{
    public static SegmentedDownloadPlan Create(long totalLength, int connectionCount)
    {
        ArgumentOutOfRangeException.ThrowIfLessThan(totalLength, 1);
        ArgumentOutOfRangeException.ThrowIfLessThan(connectionCount, 1);

        int segmentCount = checked((int)Math.Min(totalLength, connectionCount));
        long baseLength = totalLength / segmentCount;
        long remainder = totalLength % segmentCount;
        List<DownloadSegment> segments = new(segmentCount);
        long start = 0;
        for (int index = 0; index < segmentCount; index++)
        {
            long length = baseLength + (index < remainder ? 1 : 0);
            long end = checked(start + length - 1);
            segments.Add(new DownloadSegment(index, start, end));
            start = checked(end + 1);
        }

        return new SegmentedDownloadPlan(totalLength, segments);
    }

    public void Validate()
    {
        if (Segments.Count == 0 || Segments[0].Start != 0 || Segments[^1].End != TotalLength - 1)
        {
            throw new DownloadIntegrityException("The segment plan does not cover the complete file.");
        }

        for (int index = 0; index < Segments.Count; index++)
        {
            DownloadSegment segment = Segments[index];
            if (segment.Index != index || segment.End < segment.Start)
            {
                throw new DownloadIntegrityException("The segment plan contains an invalid segment.");
            }

            if (index > 0 && Segments[index - 1].End + 1 != segment.Start)
            {
                throw new DownloadIntegrityException("The segment plan contains a gap or overlap.");
            }
        }
    }
}
