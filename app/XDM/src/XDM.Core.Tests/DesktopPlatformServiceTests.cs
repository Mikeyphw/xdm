using XDM.Platform;

namespace XDM.Core.Tests;

public sealed class DesktopPlatformServiceTests
{
    [Fact]
    public async Task RejectsNonWebUrisWithoutLaunchingThem()
    {
        DesktopPlatformService service = new();

        await Assert.ThrowsAsync<ArgumentException>(
            () => service.OpenUriAsync(new Uri("file:///tmp/example")));
    }

    [Fact]
    public async Task RejectsMissingFilesBeforeShellActivation()
    {
        DesktopPlatformService service = new();
        string path = Path.Combine(Path.GetTempPath(), $"missing-{Guid.NewGuid():N}.bin");

        await Assert.ThrowsAsync<FileNotFoundException>(() => service.OpenFileAsync(path));
    }
}
