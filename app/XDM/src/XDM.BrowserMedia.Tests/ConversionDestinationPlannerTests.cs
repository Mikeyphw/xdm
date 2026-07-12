using XDM.Media;

namespace XDM.BrowserMedia.Tests;

public sealed class ConversionDestinationPlannerTests
{
    [Fact]
    public void CreatesDistinctPostDownloadDestinationForSelectedPreset()
    {
        ConversionPreset preset = Assert.Single(
            ConversionPresetCatalog.Presets,
            static candidate => string.Equals(candidate.Id, "mp3-192", StringComparison.Ordinal));
        string source = Path.Combine(Path.GetTempPath(), "example.video.mp4");

        string destination = ConversionDestinationPlanner.CreatePostDownloadDestination(source, preset);

        Assert.Equal(Path.Combine(Path.GetTempPath(), "example.video.converted.mp3"), destination);
        Assert.NotEqual(source, destination);
    }
}
