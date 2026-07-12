using XDM.Core.Scheduling;
using XDM.Persistence;

namespace XDM.DownloadEngine.Tests;

public sealed class JsonSchedulerStateStoreTests
{
    [Fact]
    public async Task RoundTripsLastEvaluationAndStartedWindows()
    {
        string directory = Path.Combine(Path.GetTempPath(), $"xdm-scheduler-state-{Guid.NewGuid():N}");
        Directory.CreateDirectory(directory);
        try
        {
            string path = Path.Combine(directory, "scheduler-state.json");
            JsonSchedulerStateStore store = new(path);
            DateTimeOffset evaluation = new(2026, 7, 12, 2, 0, 0, TimeSpan.Zero);
            SchedulerRuntimeState expected = new(
                evaluation,
                new Dictionary<string, DateTimeOffset>(StringComparer.Ordinal)
                {
                    ["night"] = evaluation.AddHours(-1)
                });

            await store.SaveAsync(expected);
            SchedulerRuntimeState actual = await store.LoadAsync();

            Assert.Equal(expected.LastEvaluationUtc, actual.LastEvaluationUtc);
            Assert.Equal(expected.LastStartedWindows["night"], actual.LastStartedWindows["night"]);
            Assert.False(File.Exists($"{path}.tmp"));
        }
        finally
        {
            Directory.Delete(directory, recursive: true);
        }
    }
}
