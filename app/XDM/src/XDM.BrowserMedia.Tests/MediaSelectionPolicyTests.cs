using XDM.Media;

namespace XDM.BrowserMedia.Tests;

public sealed class MediaSelectionPolicyTests
{
    [Fact]
    public void SelectsBoundedQualityPreferredLanguageAndSubtitles()
    {
        Uri source = new("https://media.example.test/master.m3u8");
        MediaCatalog catalog = new(
            source,
            MediaKind.Hls,
            "Example",
            false,
            [
                Format("video-2160", MediaStreamKind.Video, height: 2160, bandwidth: 18_000_000),
                Format("video-1080", MediaStreamKind.Video, height: 1080, bandwidth: 6_000_000),
                Format("video-720", MediaStreamKind.Video, height: 720, bandwidth: 3_000_000),
                Format("audio-en", MediaStreamKind.Audio, language: "en", bandwidth: 192_000),
                Format("audio-pt", MediaStreamKind.Audio, language: "pt-BR", bandwidth: 160_000),
                Format("sub-en", MediaStreamKind.Subtitle, language: "en", isDefault: true),
                Format("sub-pt", MediaStreamKind.Subtitle, language: "pt-BR")
            ],
            "test",
            "test");

        MediaSelectionResult result = MediaSelectionPolicy.Select(
            catalog,
            new MediaSelectionRequest(1080, AudioLanguage: "pt-BR", SubtitleLanguage: "pt-BR"));

        Assert.Equal("video-1080", result.Video?.Id);
        Assert.Equal("audio-pt", result.Audio?.Id);
        Assert.Equal("sub-pt", Assert.Single(result.Subtitles).Id);
    }

    [Fact]
    public void AudioOnlySkipsVideoAndUsesBestAudio()
    {
        Uri source = new("https://media.example.test/page");
        MediaCatalog catalog = new(
            source,
            MediaKind.ExternalProvider,
            "Example",
            false,
            [
                Format("video", MediaStreamKind.Video, height: 1080, bandwidth: 5_000_000),
                Format("audio-low", MediaStreamKind.Audio, bandwidth: 96_000),
                Format("audio-high", MediaStreamKind.Audio, bandwidth: 256_000)
            ],
            "test",
            "yt-dlp");

        MediaSelectionResult result = MediaSelectionPolicy.Select(
            catalog,
            new MediaSelectionRequest(AudioOnly: true));

        Assert.Null(result.Video);
        Assert.Equal("audio-high", result.Audio?.Id);
    }

    [Fact]
    public void EstimatesSelectedStreamSize()
    {
        MediaFormat video = Format("video", MediaStreamKind.Video, bandwidth: 8_000_000);
        MediaFormat audio = Format("audio", MediaStreamKind.Audio, bandwidth: 192_000);

        long? bytes = MediaSizeEstimator.EstimateBytes(TimeSpan.FromMinutes(10), video, audio);

        Assert.Equal(614_400_000L, bytes);
    }

    private static MediaFormat Format(
        string id,
        MediaStreamKind kind,
        int? height = null,
        long? bandwidth = null,
        string? language = null,
        bool isDefault = false)
        => new(
            id,
            kind,
            new Uri($"https://media.example.test/{id}"),
            kind == MediaStreamKind.Subtitle ? "vtt" : "mp4",
            null,
            bandwidth,
            height is null ? null : height * 16 / 9,
            height,
            null,
            language,
            id,
            isDefault,
            false);
}
