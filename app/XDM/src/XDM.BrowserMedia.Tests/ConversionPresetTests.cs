using XDM.Media;

namespace XDM.BrowserMedia.Tests;

public sealed class ConversionPresetTests
{
    [Fact]
    public void DeviceProfilesUseFamilySpecificArgumentRecipes()
    {
        ConversionPresetDefinition[] deviceDefinitions = ConversionPresetCatalog.Presets
            .Where(static preset => preset.Id.StartsWith("device-", StringComparison.Ordinal))
            .Select(static preset => ConversionPresetCatalog.GetDefinition(preset.Id))
            .ToArray();

        Assert.True(deviceDefinitions.Length >= 100);
        int uniqueRecipeCount = deviceDefinitions
            .Select(static definition => string.Join("\0", definition.FfmpegArguments))
            .Distinct(StringComparer.Ordinal)
            .Count();
        Assert.True(uniqueRecipeCount > 10);
        Assert.All(deviceDefinitions, static definition =>
            Assert.Contains(definition.FfmpegArguments, argument =>
                argument.StartsWith("xdm_device_family=", StringComparison.Ordinal)));
    }

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
