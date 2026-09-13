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
    [Fact]
    public async Task RecoversLastStartedWindowsFromBackupWhenPrimaryIsCorrupt()
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
                    ["night:version"] = evaluation.AddHours(-1)
                },
                new Dictionary<string, ScheduleRuntimeRunState>(StringComparer.Ordinal)
                {
                    ["run-1"] = new(
                        "run-1",
                        "night",
                        "night:version",
                        "queue",
                        evaluation.AddHours(-1),
                        evaluation,
                        ["download-1"])
                });

            await store.SaveAsync(expected);
            File.Copy(path, path + ".bak", overwrite: true);
            await File.WriteAllTextAsync(path, "{ definitely not json");

            SchedulerRuntimeState actual = await store.LoadAsync();

            Assert.Equal(expected.LastStartedWindows["night:version"], actual.LastStartedWindows["night:version"]);
            Assert.Contains("run-1", actual.ActiveRuns!.Keys);
        }
        finally
        {
            Directory.Delete(directory, recursive: true);
        }
    }

}
