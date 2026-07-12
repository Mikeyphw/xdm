using System.Net;
using System.Security.Cryptography;
using XDM.Media;

namespace XDM.BrowserMedia.Tests;

public sealed class HlsManifestParserTests
{
    [Fact]
    public void ParsesMasterVariantsAudioAndSubtitles()
    {
        HlsManifest manifest = HlsManifestParser.Parse(
            new Uri("https://media.example.test/master.m3u8"),
            MediaFixture.Read("hls-master.m3u8"));

        Assert.True(manifest.IsMaster);
        Assert.Equal(2, manifest.Variants.Count);
        Assert.Equal(1080, manifest.Variants[1].Height);
        Assert.Contains(manifest.Renditions, static rendition => rendition.Type == "AUDIO" && rendition.IsDefault);
        Assert.Contains(manifest.Renditions, static rendition => rendition.Type == "SUBTITLES" && rendition.Language == "en");
    }

    [Fact]
    public void ParsesByteRangesAndSequenceDerivedEncryption()
    {
        const string playlist = """
            #EXTM3U
            #EXT-X-MEDIA-SEQUENCE:42
            #EXT-X-KEY:METHOD=AES-128,URI="key.bin"
            #EXT-X-BYTERANGE:100@20
            #EXTINF:2,
            media.bin
            #EXT-X-BYTERANGE:50
            #EXTINF:2,
            media.bin
            #EXT-X-ENDLIST
            """;

        HlsManifest manifest = HlsManifestParser.Parse(new Uri("https://example.test/live/index.m3u8"), playlist);

        Assert.Equal(42, manifest.Segments[0].Sequence);
        Assert.Equal(20, manifest.Segments[0].ByteRangeOffset);
        Assert.Equal(120, manifest.Segments[1].ByteRangeOffset);
        Assert.Equal(new Uri("https://example.test/live/key.bin"), manifest.Segments[0].Key!.Uri);
    }

    [Fact]
    public void RejectsUnsupportedSampleAes()
    {
        const string playlist = """
            #EXTM3U
            #EXT-X-KEY:METHOD=SAMPLE-AES,URI="key.bin"
            #EXTINF:2,
            media.ts
            """;

        Assert.Throws<NotSupportedException>(() =>
            HlsManifestParser.Parse(new Uri("https://example.test/index.m3u8"), playlist));
    }

    [Fact]
    public async Task DownloaderRefreshesLiveManifestUntilEndList()
    {
        int manifestCalls = 0;
        using HttpClient client = new(new RoutingHandler(request =>
        {
            if (request.RequestUri!.AbsolutePath.EndsWith(".m3u8", StringComparison.Ordinal))
            {
                manifestCalls++;
                string manifest = manifestCalls == 1
                    ? "#EXTM3U\n#EXT-X-TARGETDURATION:1\n#EXT-X-MEDIA-SEQUENCE:1\n#EXTINF:1,\n1.ts\n"
                    : "#EXTM3U\n#EXT-X-TARGETDURATION:1\n#EXT-X-MEDIA-SEQUENCE:1\n#EXTINF:1,\n1.ts\n#EXTINF:1,\n2.ts\n#EXT-X-ENDLIST\n";
                return RoutingHandler.Text(manifest, "application/vnd.apple.mpegurl");
            }

            return RoutingHandler.Bytes(request.RequestUri.AbsolutePath.EndsWith("1.ts", StringComparison.Ordinal)
                ? "one"u8.ToArray()
                : "two"u8.ToArray());
        }));
        string workspace = Path.Combine(Path.GetTempPath(), $"xdm-hls-live-{Guid.NewGuid():N}");
        try
        {
            MediaFormat format = new("live", MediaStreamKind.Muxed, new Uri("https://example.test/live.m3u8"), "hls", null, null, null, null, null, null, "live", true, false);
            StreamDownloadResult result = await new HlsDownloader(client).DownloadAsync(
                format,
                workspace,
                MediaRequestMetadata.Empty,
                TimeSpan.FromSeconds(10),
                null,
                CancellationToken.None);

            Assert.Equal("onetwo"u8.ToArray(), await File.ReadAllBytesAsync(result.Path));
            Assert.True(result.IsLive);
            Assert.Equal(2, manifestCalls);
        }
        finally
        {
            if (Directory.Exists(workspace))
            {
                Directory.Delete(workspace, recursive: true);
            }
        }
    }

    [Fact]
    public async Task DownloaderResumesFromFragmentCheckpointWithoutRefetchingCompletedSegments()
    {
        int segmentCalls = 0;
        using HttpClient client = new(new RoutingHandler(request =>
        {
            if (request.RequestUri!.AbsolutePath.EndsWith(".m3u8", StringComparison.Ordinal))
            {
                return RoutingHandler.Text(
                    "#EXTM3U\n#EXT-X-TARGETDURATION:2\n#EXT-X-MEDIA-SEQUENCE:1\n#EXTINF:2,\n1.ts\n#EXTINF:2,\n2.ts\n#EXT-X-ENDLIST\n",
                    "application/vnd.apple.mpegurl");
            }

            segmentCalls++;
            return RoutingHandler.Bytes(request.RequestUri.AbsolutePath.EndsWith("1.ts", StringComparison.Ordinal)
                ? "one"u8.ToArray()
                : "two"u8.ToArray());
        }));
        string workspace = Path.Combine(Path.GetTempPath(), $"xdm-hls-resume-{Guid.NewGuid():N}");
        try
        {
            MediaFormat format = new("resume", MediaStreamKind.Muxed, new Uri("https://example.test/vod.m3u8"), "hls", null, null, null, null, null, null, "resume", true, false);
            HlsDownloader downloader = new(client);
            await downloader.DownloadAsync(format, workspace, MediaRequestMetadata.Empty, null, null, CancellationToken.None);
            await downloader.DownloadAsync(format, workspace, MediaRequestMetadata.Empty, null, null, CancellationToken.None);

            Assert.Equal(2, segmentCalls);
        }
        finally
        {
            if (Directory.Exists(workspace))
            {
                Directory.Delete(workspace, recursive: true);
            }
        }
    }

    [Fact]
    public async Task DownloaderDecryptsAes128AndRetriesFragment()
    {
        byte[] key = Enumerable.Range(1, 16).Select(static value => (byte)value).ToArray();
        byte[] plain = "deterministic transport stream payload"u8.ToArray();
        byte[] iv = new byte[16];
        System.Buffers.Binary.BinaryPrimitives.WriteInt64BigEndian(iv.AsSpan(8), 7);
        byte[] encrypted;
        using (Aes aes = Aes.Create())
        {
            aes.Key = key;
            aes.IV = iv;
            aes.Mode = CipherMode.CBC;
            aes.Padding = PaddingMode.PKCS7;
            using ICryptoTransform encryptor = aes.CreateEncryptor();
            encrypted = encryptor.TransformFinalBlock(plain, 0, plain.Length);
        }

        int segmentCalls = 0;
        using HttpClient client = new(new RoutingHandler(request =>
        {
            string path = request.RequestUri!.AbsolutePath;
            if (path.EndsWith(".m3u8", StringComparison.Ordinal))
            {
                return RoutingHandler.Text(MediaFixture.Read("hls-vod.m3u8"), "application/vnd.apple.mpegurl");
            }

            if (path.EndsWith("key.bin", StringComparison.Ordinal))
            {
                return RoutingHandler.Bytes(key);
            }

            segmentCalls++;
            return segmentCalls == 1
                ? new HttpResponseMessage(HttpStatusCode.ServiceUnavailable)
                : RoutingHandler.Bytes(encrypted);
        }));
        string workspace = Path.Combine(Path.GetTempPath(), $"xdm-hls-test-{Guid.NewGuid():N}");
        try
        {
            MediaFormat format = new(
                "hls-main",
                MediaStreamKind.Muxed,
                new Uri("https://example.test/vod.m3u8"),
                "hls",
                null,
                null,
                null,
                null,
                null,
                null,
                "main",
                true,
                true);
            StreamDownloadResult result = await new HlsDownloader(client).DownloadAsync(
                format,
                workspace,
                MediaRequestMetadata.Empty,
                null,
                null,
                CancellationToken.None);

            Assert.Equal(plain, await File.ReadAllBytesAsync(result.Path));
            Assert.Equal(2, segmentCalls);
            Assert.True(File.Exists(Path.Combine(workspace, "hls-main", "checkpoint.json")));
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
