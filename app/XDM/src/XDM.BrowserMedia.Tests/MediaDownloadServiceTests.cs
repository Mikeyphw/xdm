using XDM.Media;

namespace XDM.BrowserMedia.Tests;

public sealed class MediaDownloadServiceTests
{
    [Fact]
    public async Task DownloadsDirectMediaAtomically()
    {
        byte[] payload = "direct media"u8.ToArray();
        using HttpClient client = new(new RoutingHandler(_ => RoutingHandler.Bytes(payload)));
        Uri source = new("https://media.example.test/video.mp4");
        MediaCatalog catalog = new(
            source,
            MediaKind.DirectFile,
            "video.mp4",
            false,
            [new MediaFormat("direct", MediaStreamKind.Muxed, source, "mp4", null, null, null, null, null, null, "direct", true, false)],
            "direct",
            "direct");
        string directory = Path.Combine(Path.GetTempPath(), $"xdm-media-direct-{Guid.NewGuid():N}");
        string destination = Path.Combine(directory, "video.mp4");
        try
        {
            MediaDownloadService service = new(client, new FixedCatalogService(catalog), new FakeFfmpegService());

            MediaDownloadResult result = await service.DownloadAsync(new MediaDownloadRequest(source, destination));

            Assert.Equal(payload, await File.ReadAllBytesAsync(destination));
            Assert.False(result.UsedFfmpeg);
            Assert.False(File.Exists($"{destination}.xdm-finalizing"));
        }
        finally
        {
            if (Directory.Exists(directory))
            {
                Directory.Delete(directory, recursive: true);
            }
        }
    }

    [Fact]
    public async Task DownloadsHlsAndFinalizesThroughFfmpeg()
    {
        byte[] payload = "transport stream"u8.ToArray();
        using HttpClient client = new(new RoutingHandler(request =>
            request.RequestUri!.AbsolutePath.EndsWith(".m3u8", StringComparison.Ordinal)
                ? RoutingHandler.Text(
                    "#EXTM3U\n#EXT-X-TARGETDURATION:4\n#EXTINF:4,\nsegment.ts\n#EXT-X-ENDLIST\n",
                    "application/vnd.apple.mpegurl")
                : RoutingHandler.Bytes(payload)));
        Uri source = new("https://media.example.test/video.m3u8");
        MediaFormat format = new("hls-main", MediaStreamKind.Muxed, source, "hls", null, null, null, null, null, null, "main", true, false);
        MediaCatalog catalog = new(source, MediaKind.Hls, "video", false, [format], "hls", "native-hls");
        FakeFfmpegService ffmpeg = new();
        string directory = Path.Combine(Path.GetTempPath(), $"xdm-media-hls-{Guid.NewGuid():N}");
        string destination = Path.Combine(directory, "video.mp4");
        try
        {
            MediaDownloadService service = new(client, new FixedCatalogService(catalog), ffmpeg);

            MediaDownloadResult result = await service.DownloadAsync(new MediaDownloadRequest(source, destination));

            Assert.True(result.UsedFfmpeg);
            Assert.Equal(payload, await File.ReadAllBytesAsync(destination));
            Assert.Equal(1, ffmpeg.MuxCalls);
        }
        finally
        {
            if (Directory.Exists(directory))
            {
                Directory.Delete(directory, recursive: true);
            }
        }
    }

    private sealed class FixedCatalogService(MediaCatalog catalog) : IMediaCatalogService
    {
        public Task<MediaCatalog> GetCatalogAsync(
            Uri source,
            MediaRequestMetadata? metadata = null,
            CancellationToken cancellationToken = default)
            => Task.FromResult(catalog);
    }

    private sealed class FakeFfmpegService : IFfmpegService
    {
        public int MuxCalls { get; private set; }

        public Task<ExternalToolHealth> GetHealthAsync(CancellationToken cancellationToken = default)
            => Task.FromResult(new ExternalToolHealth("FFmpeg", true, "fake", "fake", "ok"));

        public async Task MuxAsync(
            IReadOnlyList<string> inputPaths,
            string destinationPath,
            CancellationToken cancellationToken = default)
        {
            MuxCalls++;
            Directory.CreateDirectory(Path.GetDirectoryName(destinationPath)!);
            await using FileStream destination = new(destinationPath, FileMode.Create, FileAccess.Write, FileShare.None);
            foreach (string inputPath in inputPaths)
            {
                await using FileStream source = File.OpenRead(inputPath);
                await source.CopyToAsync(destination, cancellationToken);
            }
        }
    }
}
