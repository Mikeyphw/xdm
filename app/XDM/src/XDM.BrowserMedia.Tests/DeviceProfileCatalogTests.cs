using XDM.Media;

namespace XDM.BrowserMedia.Tests;

public sealed class DeviceProfileCatalogTests
{
    [Fact]
    public void ExposesMoreThanOneHundredDeviceProfiles()
    {
        ConversionPreset[] deviceProfiles = ConversionPresetCatalog.Presets
            .Where(static preset => preset.Id.StartsWith("device-", StringComparison.Ordinal))
            .ToArray();

        Assert.True(deviceProfiles.Length >= 100);
        Assert.Equal(deviceProfiles.Length, deviceProfiles.Select(static preset => preset.Id).Distinct(StringComparer.Ordinal).Count());
        Assert.Contains(deviceProfiles, static preset => preset.Name.Contains("Apple iPhone", StringComparison.Ordinal));
        Assert.Contains(deviceProfiles, static preset => preset.Name.Contains("Smart TV", StringComparison.Ordinal));
        Assert.Contains(deviceProfiles, static preset => preset.Name.Contains("PlayStation", StringComparison.Ordinal));
    }

    [Fact]
    public void DeviceArgumentsAreFixedAndNeverContainShellTokens()
    {
        ConversionPreset preset = Assert.Single(
            ConversionPresetCatalog.Presets,
            static item => item.Id == "device-apple-iphone-1080p");
        ConversionPresetDefinition definition = ConversionPresetCatalog.GetDefinition(preset.Id);

        Assert.Contains("libx264", definition.FfmpegArguments);
        Assert.DoesNotContain(definition.FfmpegArguments, static argument => argument.Contains(';'));
        Assert.DoesNotContain(definition.FfmpegArguments, static argument => argument.Contains("&&", StringComparison.Ordinal));
    }
}
