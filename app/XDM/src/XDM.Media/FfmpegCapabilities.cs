namespace XDM.Media;

public sealed record FfmpegCapabilities(
    ExternalToolHealth Health,
    bool SupportsH264,
    bool SupportsH265,
    bool SupportsAv1,
    bool SupportsAac,
    bool SupportsMp3,
    bool SupportsOpus)
{
    public string Summary
    {
        get
        {
            if (!Health.IsAvailable)
            {
                return Health.Message;
            }

            List<string> capabilities = [];
            if (SupportsH264) capabilities.Add("H.264");
            if (SupportsH265) capabilities.Add("H.265");
            if (SupportsAv1) capabilities.Add("AV1");
            if (SupportsAac) capabilities.Add("AAC");
            if (SupportsMp3) capabilities.Add("MP3");
            if (SupportsOpus) capabilities.Add("Opus");
            return capabilities.Count == 0
                ? "FFmpeg is available for remuxing; encoder capabilities were not reported."
                : $"FFmpeg encoders: {string.Join(", ", capabilities)}.";
        }
    }
}
