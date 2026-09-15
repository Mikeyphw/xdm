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


    [Fact]
    public async Task StopsDirectMediaAtConfiguredSizeLimit()
    {
        byte[] payload = new byte[4096];
        using HttpClient client = new(new RoutingHandler(_ => RoutingHandler.Bytes(payload)));
        Uri source = new("https://media.example.test/large.mp4");
        MediaCatalog catalog = new(
            source,
            MediaKind.DirectFile,
            "large.mp4",
            false,
            [new MediaFormat("direct", MediaStreamKind.Muxed, source, "mp4", null, null, null, null, null, null, "direct", true, false)],
            "direct",
            "direct");
        string directory = Path.Combine(Path.GetTempPath(), $"xdm-media-limit-{Guid.NewGuid():N}");
        string destination = Path.Combine(directory, "large.mp4");
        try
        {
            MediaDownloadService service = new(client, new FixedCatalogService(catalog), new FakeFfmpegService());

            await Assert.ThrowsAsync<InvalidDataException>(() => service.DownloadAsync(
                new MediaDownloadRequest(source, destination, MaximumBytes: 1024)));

            Assert.False(File.Exists(destination));
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
    public async Task PublishesSubtitleTracksAfterMainMediaWithStableUniqueNames()
    {
        using HttpClient client = new(new RoutingHandler(request =>
        {
            string path = request.RequestUri!.AbsolutePath;
            if (path.EndsWith("captions-one.srt", StringComparison.Ordinal))
            {
                return RoutingHandler.Bytes("first subtitle"u8.ToArray());
            }

            if (path.EndsWith("captions-two.ttml", StringComparison.Ordinal))
            {
                return RoutingHandler.Bytes("second subtitle"u8.ToArray());
            }

            return RoutingHandler.Bytes("main video"u8.ToArray());
        }));
        Uri source = new("https://media.example.test/watch");
        MediaCatalog catalog = new(
            source,
            MediaKind.DirectFile,
            "video",
            false,
            [
                new MediaFormat("video", MediaStreamKind.Muxed, new Uri("https://media.example.test/video.mp4"), "mp4", null, null, null, null, null, null, "video", true, false),
                new MediaFormat("sub-one", MediaStreamKind.Subtitle, new Uri("https://media.example.test/captions-one.srt"), "srt", null, null, null, null, null, "en", "English", false, false),
                new MediaFormat("sub-two", MediaStreamKind.Subtitle, new Uri("https://media.example.test/captions-two.ttml"), "ttml", null, null, null, null, null, "en", "English", false, false)
            ],
            "direct",
            "direct");
        string directory = Path.Combine(Path.GetTempPath(), $"xdm-media-subtitles-{Guid.NewGuid():N}");
        string destination = Path.Combine(directory, "video.mp4");
        try
        {
            MediaDownloadService service = new(client, new FixedCatalogService(catalog), new FakeFfmpegService());

            MediaDownloadResult result = await service.DownloadAsync(new MediaDownloadRequest(
                source,
                destination,
                SubtitleFormatIds: ["sub-one", "sub-two"]));

            Assert.Equal("main video", await File.ReadAllTextAsync(destination));
            Assert.Collection(
                result.SubtitlePaths.OrderBy(static path => path, StringComparer.OrdinalIgnoreCase),
                first =>
                {
                    Assert.EndsWith("video.en.2.ttml", first, StringComparison.OrdinalIgnoreCase);
                    Assert.Equal("second subtitle", File.ReadAllText(first));
                },
                second =>
                {
                    Assert.EndsWith("video.en.srt", second, StringComparison.OrdinalIgnoreCase);
                    Assert.Equal("first subtitle", File.ReadAllText(second));
                });
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
    public async Task DoesNotPublishSubtitlesWhenMainMuxFails()
    {
        using HttpClient client = new(new RoutingHandler(request =>
        {
            string path = request.RequestUri!.AbsolutePath;
            if (path.EndsWith("captions.srt", StringComparison.Ordinal))
            {
                return RoutingHandler.Bytes("subtitle"u8.ToArray());
            }

            return RoutingHandler.Bytes(Path.GetFileName(path) switch
            {
                "audio.m4a" => "audio"u8.ToArray(),
                _ => "video"u8.ToArray()
            });
        }));
        Uri source = new("https://media.example.test/watch");
        MediaCatalog catalog = new(
            source,
            MediaKind.DirectFile,
            "video",
            false,
            [
                new MediaFormat("video", MediaStreamKind.Video, new Uri("https://media.example.test/video.mp4"), "mp4", null, null, null, null, null, null, "video", true, false),
                new MediaFormat("audio", MediaStreamKind.Audio, new Uri("https://media.example.test/audio.m4a"), "m4a", null, null, null, null, null, null, "audio", true, false),
                new MediaFormat("sub", MediaStreamKind.Subtitle, new Uri("https://media.example.test/captions.srt"), "srt", null, null, null, null, null, "en", "English", false, false)
            ],
            "direct",
            "direct");
        string directory = Path.Combine(Path.GetTempPath(), $"xdm-media-subtitle-fail-{Guid.NewGuid():N}");
        string destination = Path.Combine(directory, "video.mp4");
        try
        {
            MediaDownloadService service = new(client, new FixedCatalogService(catalog), new FakeFfmpegService { FailMux = true });

            await Assert.ThrowsAsync<InvalidOperationException>(() => service.DownloadAsync(new MediaDownloadRequest(
                source,
                destination,
                VideoFormatId: "video",
                AudioFormatId: "audio",
                SubtitleFormatIds: ["sub"])));

            Assert.False(File.Exists(destination));
            Assert.DoesNotContain(Directory.EnumerateFiles(directory, "*.srt", SearchOption.TopDirectoryOnly), File.Exists);
            Assert.DoesNotContain(Directory.EnumerateFiles(directory, "*.xdm-finalizing*", SearchOption.TopDirectoryOnly), _ => true);
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
    public async Task RejectsIncompatibleStreamCopyBeforeMuxingToMp4()
    {
        using HttpClient client = new(new RoutingHandler(request => RoutingHandler.Bytes(Path.GetFileName(request.RequestUri!.AbsolutePath) switch
        {
            "audio.opus" => "audio"u8.ToArray(),
            _ => "video"u8.ToArray()
        })));
        Uri source = new("https://media.example.test/watch");
        MediaCatalog catalog = new(
            source,
            MediaKind.DirectFile,
            "video",
            false,
            [
                new MediaFormat("video", MediaStreamKind.Video, new Uri("https://media.example.test/video.webm"), "webm", "vp9", null, null, null, null, null, "video", true, false),
                new MediaFormat("audio", MediaStreamKind.Audio, new Uri("https://media.example.test/audio.opus"), "opus", "opus", null, null, null, null, null, "audio", true, false)
            ],
            "direct",
            "direct");
        string directory = Path.Combine(Path.GetTempPath(), $"xdm-media-mux-compat-{Guid.NewGuid():N}");
        string destination = Path.Combine(directory, "video.mp4");
        FakeFfmpegService ffmpeg = new();
        try
        {
            MediaDownloadService service = new(client, new FixedCatalogService(catalog), ffmpeg);

            InvalidDataException exception = await Assert.ThrowsAsync<InvalidDataException>(() => service.DownloadAsync(new MediaDownloadRequest(
                source,
                destination,
                VideoFormatId: "video",
                AudioFormatId: "audio")));

            Assert.Contains("Cannot stream-copy", exception.Message, StringComparison.Ordinal);
            Assert.Equal(0, ffmpeg.MuxCalls);
            Assert.False(File.Exists(destination));
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
    public void ScavengesOldMediaFinalizationArtifacts()
    {
        string directory = Path.Combine(Path.GetTempPath(), $"xdm-media-finalizing-scavenge-{Guid.NewGuid():N}");
        Directory.CreateDirectory(directory);
        string oldArtifact = Path.Combine(directory, ".video.xdm-finalizing.mp4");
        string freshArtifact = Path.Combine(directory, ".fresh.xdm-finalizing.mp4");
        File.WriteAllText(oldArtifact, "old");
        File.WriteAllText(freshArtifact, "fresh");
        File.SetLastWriteTimeUtc(oldArtifact, DateTime.UtcNow - TimeSpan.FromDays(2));
        try
        {
            int deleted = MediaDownloadService.ScavengeAbandonedFinalizationFiles(directory, TimeSpan.FromHours(12));

            Assert.Equal(1, deleted);
            Assert.False(File.Exists(oldArtifact));
            Assert.True(File.Exists(freshArtifact));
        }
        finally
        {
            Directory.Delete(directory, recursive: true);
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

        public bool FailMux { get; init; }

        public Task<ExternalToolHealth> GetHealthAsync(CancellationToken cancellationToken = default)
            => Task.FromResult(new ExternalToolHealth("FFmpeg", true, "fake", "fake", "ok"));

        public async Task MuxAsync(
            IReadOnlyList<string> inputPaths,
            string destinationPath,
            CancellationToken cancellationToken = default)
        {
            MuxCalls++;
            if (FailMux)
            {
                throw new InvalidOperationException("mux failed");
            }

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
