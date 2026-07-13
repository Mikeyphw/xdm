namespace XDM.Media;

public static class MediaSizeEstimator
{
    public static long? EstimateBytes(
        TimeSpan? duration,
        MediaFormat? video,
        MediaFormat? audio)
    {
        if (duration is not TimeSpan knownDuration || knownDuration <= TimeSpan.Zero)
        {
            return null;
        }

        long bandwidth = checked((video?.Bandwidth ?? 0) + (audio?.Bandwidth ?? 0));
        if (bandwidth <= 0)
        {
            return null;
        }

        double estimated = bandwidth / 8d * knownDuration.TotalSeconds;
        return estimated >= long.MaxValue ? long.MaxValue : checked((long)Math.Ceiling(estimated));
    }
}
