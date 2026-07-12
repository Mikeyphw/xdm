using XDM.Media;

namespace XDM.BrowserMedia.Tests;

public sealed class ConversionPresetTests
{
    [Fact]
    public void PresetsHaveUniqueStableIdsAndSupportedExtensions()
    {
        IReadOnlyList<ConversionPreset> presets = ConversionPresetCatalog.Presets;

        Assert.NotEmpty(presets);
        Assert.Equal(presets.Count, presets.Select(static preset => preset.Id).Distinct(StringComparer.Ordinal).Count());
        Assert.All(presets, static preset =>
        {
            Assert.False(string.IsNullOrWhiteSpace(preset.Name));
            Assert.True(preset.FileExtension is ".mp4" or ".mp3");
        });
        Assert.Contains(presets, static preset => preset.Kind == ConversionKind.Remux);
        Assert.Contains(presets, static preset => preset.Kind == ConversionKind.VideoTranscode);
        Assert.Contains(presets, static preset => preset.Kind == ConversionKind.AudioExtraction);
    }
}
