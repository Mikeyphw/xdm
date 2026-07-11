using System.Runtime.InteropServices;

namespace XDM.Platform;

public sealed class PlatformInfo : IPlatformInfo
{
    public string OperatingSystem { get; } = RuntimeInformation.OSDescription.Trim();

    public string Architecture { get; } = RuntimeInformation.OSArchitecture.ToString();

    public string Runtime { get; } = RuntimeInformation.FrameworkDescription.Trim();

    public string DisplayName => $"{OperatingSystem} • {Architecture}";
}
