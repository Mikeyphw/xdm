using XDM.App.Services;
using XDM.BrowserIntegration;

namespace XDM.App.Tests;

public sealed class BrowserCaptureAcknowledgementStoreTests
{
    [Fact]
    public void AcknowledgementSurvivesStoreReload()
    {
        string path = Path.Combine(Path.GetTempPath(), $"xdm-browser-acks-{Guid.NewGuid():N}.json");
        try
        {
            BrowserCaptureAcknowledgementStore first = new(path);
            first.Save(new BrowserCaptureAcknowledgement("capture-1", true, "accepted", "download-1"));

            BrowserCaptureAcknowledgementStore second = new(path);
            BrowserCaptureAcknowledgement? restored = second.TryGet("capture-1");

            Assert.NotNull(restored);
            Assert.True(restored!.Accepted);
            Assert.Equal("download-1", restored.DownloadId);
        }
        finally
        {
            if (File.Exists(path))
            {
                File.Delete(path);
            }
        }
    }
}
