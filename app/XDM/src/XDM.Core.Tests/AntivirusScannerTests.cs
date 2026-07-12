using XDM.Core.Scheduling;
using XDM.Platform;

namespace XDM.Core.Tests;

public sealed class AntivirusScannerTests
{
    private static readonly string[] ScanArguments = ["--scan", "{file}", "literal;not-shell"];
    [Fact]
    public async Task ReplacesFilePlaceholderAndDoesNotInvokeShell()
    {
        string executable = Path.GetTempFileName();
        string target = Path.GetTempFileName();
        try
        {
            RecordingRunner runner = new();
            AntivirusScanner scanner = new(runner);
            AntivirusScanSettings settings = new(
                true,
                executable,
                ScanArguments,
                30);

            AntivirusScanResult result = await scanner.ScanAsync(target, settings);

            Assert.True(result.Succeeded);
            string[] expected = ["--scan", target, "literal;not-shell"];
            Assert.Equal(expected, runner.Arguments);
        }
        finally
        {
            File.Delete(executable);
            File.Delete(target);
        }
    }

    private sealed class RecordingRunner : IPlatformCommandRunner
    {
        public IReadOnlyList<string> Arguments { get; private set; } = [];

        public Task<PlatformCommandResult> RunAsync(
            string executablePath,
            IReadOnlyList<string> arguments,
            TimeSpan timeout,
            CancellationToken cancellationToken = default)
        {
            Arguments = arguments.ToArray();
            return Task.FromResult(new PlatformCommandResult(0, string.Empty, string.Empty, false));
        }
    }
}
