using XDM.Media;

namespace XDM.BrowserMedia.Tests;

public sealed class ConversionServiceTests
{
    [Fact]
    public async Task ExtractsMp3WithFixedSafeArgumentsAndAtomicPublication()
    {
        string directory = CreateTempDirectory("xdm-conversion-mp3");
        string source = Path.Combine(directory, "source video.mkv");
        string destination = Path.Combine(directory, "output audio.mp3");
        await File.WriteAllTextAsync(source, "source");
        RecordingProcessRunner process = new(static (_, arguments, _, _, _) =>
        {
            File.WriteAllText(arguments[^1], "converted-audio");
            return Task.FromResult(new ConversionProcessResult(0, string.Empty, TimeSpan.FromSeconds(1)));
        });
        ConversionService service = CreateService(
            process,
            new MediaInspection(TimeSpan.FromSeconds(30), "matroska", "h264", "aac", true, true));
        try
        {
            ConversionResult result = await service.ConvertAsync(
                new ConversionRequest(source, destination, "mp3-192"));

            Assert.Equal("converted-audio", await File.ReadAllTextAsync(destination));
            Assert.Equal(new FileInfo(destination).Length, result.OutputBytes);
            IReadOnlyList<string> arguments = Assert.Single(process.Calls);
            Assert.Contains("-vn", arguments);
            Assert.Contains("libmp3lame", arguments);
            Assert.Contains(source, arguments);
            Assert.DoesNotContain("sh", arguments);
            Assert.DoesNotContain("cmd.exe", arguments);
            Assert.Empty(Directory.EnumerateFiles(directory, "*.xdm-converting"));
        }
        finally
        {
            Directory.Delete(directory, recursive: true);
        }
    }

    [Fact]
    public async Task RejectsIncompatibleMp4RemuxBeforeStartingFfmpeg()
    {
        string directory = CreateTempDirectory("xdm-conversion-remux");
        string source = Path.Combine(directory, "source.webm");
        string destination = Path.Combine(directory, "output.mp4");
        await File.WriteAllTextAsync(source, "source");
        RecordingProcessRunner process = new(static (_, _, _, _, _) =>
            throw new InvalidOperationException("FFmpeg must not run."));
        ConversionService service = CreateService(
            process,
            new MediaInspection(TimeSpan.FromSeconds(10), "webm", "vp9", "opus", true, true));
        try
        {
            InvalidDataException exception = await Assert.ThrowsAsync<InvalidDataException>(() =>
                service.ConvertAsync(new ConversionRequest(source, destination, "mp4-copy")));

            Assert.Contains("cannot be copied safely", exception.Message, StringComparison.Ordinal);
            Assert.Empty(process.Calls);
            Assert.False(File.Exists(destination));
        }
        finally
        {
            Directory.Delete(directory, recursive: true);
        }
    }

    [Fact]
    public async Task FailedConversionPreservesExistingDestination()
    {
        string directory = CreateTempDirectory("xdm-conversion-atomic");
        string source = Path.Combine(directory, "source.mkv");
        string destination = Path.Combine(directory, "output.mp4");
        await File.WriteAllTextAsync(source, "source");
        await File.WriteAllTextAsync(destination, "existing");
        RecordingProcessRunner process = new(static (_, arguments, _, _, _) =>
        {
            File.WriteAllText(arguments[^1], "partial");
            return Task.FromResult(new ConversionProcessResult(1, "encoder failure", TimeSpan.FromSeconds(1)));
        });
        ConversionService service = CreateService(
            process,
            new MediaInspection(TimeSpan.FromSeconds(10), "matroska", "h264", "aac", true, true));
        try
        {
            InvalidOperationException exception = await Assert.ThrowsAsync<InvalidOperationException>(() =>
                service.ConvertAsync(new ConversionRequest(source, destination, "mp4-h264-balanced", true)));

            Assert.Contains("encoder failure", exception.Message, StringComparison.Ordinal);
            Assert.Equal("existing", await File.ReadAllTextAsync(destination));
            Assert.Empty(Directory.EnumerateFiles(directory, "*.xdm-converting"));
        }
        finally
        {
            Directory.Delete(directory, recursive: true);
        }
    }

    [Fact]
    public async Task ValidatesPresetDestinationExtension()
    {
        string directory = CreateTempDirectory("xdm-conversion-extension");
        string source = Path.Combine(directory, "source.mkv");
        await File.WriteAllTextAsync(source, "source");
        ConversionService service = CreateService(
            new RecordingProcessRunner(static (_, _, _, _, _) =>
                throw new InvalidOperationException("FFmpeg must not run.")),
            new MediaInspection(TimeSpan.FromSeconds(10), "matroska", "h264", "aac", true, true));
        try
        {
            ArgumentException exception = await Assert.ThrowsAsync<ArgumentException>(() =>
                service.ConvertAsync(new ConversionRequest(source, Path.Combine(directory, "output.mp3"), "mp4-copy")));

            Assert.Contains("requires a '.mp4' destination", exception.Message, StringComparison.Ordinal);
        }
        finally
        {
            Directory.Delete(directory, recursive: true);
        }
    }

    private static ConversionService CreateService(
        RecordingProcessRunner processRunner,
        MediaInspection inspection)
        => new(
            new NoOpExternalToolRunner(),
            processRunner,
            new FixedInspectionService(inspection),
            "/fake/ffmpeg",
            "/fake/ffprobe");

    private static string CreateTempDirectory(string prefix)
    {
        string directory = Path.Combine(Path.GetTempPath(), $"{prefix}-{Guid.NewGuid():N}");
        Directory.CreateDirectory(directory);
        return directory;
    }

    private sealed class RecordingProcessRunner(
        Func<string, IReadOnlyList<string>, TimeSpan?, IProgress<ConversionProgress>?, CancellationToken, Task<ConversionProcessResult>> callback)
        : IConversionProcessRunner
    {
        public List<IReadOnlyList<string>> Calls { get; } = [];

        public Task<ConversionProcessResult> RunAsync(
            string executablePath,
            IReadOnlyList<string> arguments,
            TimeSpan? expectedDuration,
            IProgress<ConversionProgress>? progress,
            CancellationToken cancellationToken)
        {
            Calls.Add(arguments.ToArray());
            return callback(executablePath, arguments, expectedDuration, progress, cancellationToken);
        }
    }

    private sealed class FixedInspectionService(MediaInspection inspection) : IMediaInspectionService
    {
        public Task<MediaInspection> InspectAsync(
            string sourcePath,
            CancellationToken cancellationToken = default)
            => Task.FromResult(inspection);
    }

    private sealed class NoOpExternalToolRunner : IExternalToolRunner
    {
        public Task<ExternalToolResult> RunAsync(
            string executablePath,
            IReadOnlyList<string> arguments,
            TimeSpan timeout,
            int maximumOutputBytes,
            CancellationToken cancellationToken = default)
            => Task.FromResult(new ExternalToolResult(0, "ffmpeg version test", string.Empty));
    }
}
