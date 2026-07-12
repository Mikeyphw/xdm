namespace XDM.DownloadEngine.Aria2;

public sealed record Aria2Health(
    bool IsAvailable,
    bool IsManagedProcessRunning,
    string Message,
    string? Version = null)
{
    public static Aria2Health Disabled { get; } = new(
        false,
        false,
        "aria2 integration is disabled.");
}
