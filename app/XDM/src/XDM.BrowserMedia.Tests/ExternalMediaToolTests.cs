using XDM.Media;

namespace XDM.BrowserMedia.Tests;

public sealed class ExternalMediaToolTests
{
    [Fact]
    public async Task FfmpegHealthAndMuxUseArgumentListWithoutShell()
    {
        RecordingRunner runner = new((_, arguments) =>
            arguments.Contains("-version", StringComparer.Ordinal)
                ? new ExternalToolResult(0, "ffmpeg version test\n", string.Empty)
                : new ExternalToolResult(0, string.Empty, string.Empty));
        FfmpegService service = new(runner, "/fake/ffmpeg");

        ExternalToolHealth health = await service.GetHealthAsync();
        await service.MuxAsync(["/tmp/input one.mp4", "/tmp/input two.m4a"], "/tmp/output file.mp4");

        Assert.True(health.IsAvailable);
        Assert.Equal(2, runner.Calls.Count);
        Assert.Equal("/fake/ffmpeg", runner.Calls[1].Executable);
        Assert.Contains("/tmp/input one.mp4", runner.Calls[1].Arguments);
        Assert.Contains("/tmp/output file.mp4", runner.Calls[1].Arguments);
        Assert.DoesNotContain("sh", runner.Calls[1].Arguments);
        Assert.DoesNotContain("cmd.exe", runner.Calls[1].Arguments);
    }

    [Fact]
    public async Task YtDlpUsesPrivateTemporaryConfigForSensitiveHeaders()
    {
        string? configPath = null;
        string? configContent = null;
        RecordingRunner runner = new((_, arguments) =>
        {
            int index = arguments.IndexOf("--config-locations");
            if (index >= 0)
            {
                configPath = arguments[index + 1];
                configContent = File.ReadAllText(configPath);
            }

            return new ExternalToolResult(0, MediaFixture.Read("ytdlp-catalog.json"), string.Empty);
        });
        YtDlpProvider provider = new(runner, "/fake/yt-dlp");
        MediaRequestMetadata metadata = new(
            new Dictionary<string, string> { ["Authorization"] = "Bearer secret" },
            "session=secret-cookie",
            "https://example.test/page",
            "XDM test agent");

        MediaCatalog? catalog = await provider.TryGetCatalogAsync(
            new Uri("https://example.test/watch?v=1"),
            metadata);

        Assert.NotNull(catalog);
        Assert.NotNull(configPath);
        Assert.False(File.Exists(configPath));
        Assert.Contains("secret-cookie", configContent!, StringComparison.Ordinal);
        Assert.Contains("Bearer secret", configContent!, StringComparison.Ordinal);
        Assert.DoesNotContain(runner.Calls[0].Arguments, argument => argument.Contains("secret-cookie", StringComparison.Ordinal));
        Assert.Equal("--", runner.Calls[0].Arguments[^2]);
        Assert.Equal("https://example.test/watch?v=1", runner.Calls[0].Arguments[^1]);
    }

    private sealed class RecordingRunner(Func<string, IReadOnlyList<string>, ExternalToolResult> response) : IExternalToolRunner
    {
        public List<Call> Calls { get; } = [];

        public Task<ExternalToolResult> RunAsync(
            string executablePath,
            IReadOnlyList<string> arguments,
            TimeSpan timeout,
            int maximumOutputBytes,
            CancellationToken cancellationToken = default)
        {
            Calls.Add(new Call(executablePath, arguments.ToArray()));
            return Task.FromResult(response(executablePath, arguments));
        }
    }

    private sealed record Call(string Executable, IReadOnlyList<string> Arguments);
}

internal static class ReadOnlyListExtensions
{
    public static int IndexOf(this IReadOnlyList<string> items, string value)
    {
        for (int index = 0; index < items.Count; index++)
        {
            if (string.Equals(items[index], value, StringComparison.Ordinal))
            {
                return index;
            }
        }

        return -1;
    }
}
