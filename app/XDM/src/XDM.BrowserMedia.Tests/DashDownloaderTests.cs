using XDM.Media;

namespace XDM.BrowserMedia.Tests;

public sealed class DashDownloaderTests
{
    [Fact]
    public async Task DownloadsInitializationAndTimelineFragmentsInOrder()
    {
        using HttpClient client = new(new RoutingHandler(request =>
        {
            string path = request.RequestUri!.AbsolutePath;
            if (path.EndsWith("manifest.mpd", StringComparison.Ordinal))
            {
                return RoutingHandler.Text(MediaFixture.Read("dash-static.mpd"), "application/dash+xml");
            }

            return RoutingHandler.Bytes(System.Text.Encoding.UTF8.GetBytes(Path.GetFileName(path) + "|"));
        }));
        string workspace = Path.Combine(Path.GetTempPath(), $"xdm-dash-{Guid.NewGuid():N}");
        try
        {
            MediaFormat format = new(
                "dash-video-v1080",
                MediaStreamKind.Video,
                new Uri("https://media.example.test/path/manifest.mpd"),
                "video/mp4",
                "avc1",
                2_800_000,
                1920,
                1080,
                30,
                null,
                "1080p",
                true,
                false,
                "v1080");
            StreamDownloadResult result = await new DashDownloader(client).DownloadAsync(
                format,
                workspace,
                MediaRequestMetadata.Empty,
                null,
                null,
                CancellationToken.None);

            string content = await File.ReadAllTextAsync(result.Path);
            Assert.Equal("init-v1080.mp4|chunk-v1080-00001.m4s|chunk-v1080-00002.m4s|chunk-v1080-00003.m4s|", content);
            Assert.Equal(3, result.FragmentCount);
        }
        finally
        {
            if (Directory.Exists(workspace))
            {
                Directory.Delete(workspace, recursive: true);
            }
        }
    }
}
