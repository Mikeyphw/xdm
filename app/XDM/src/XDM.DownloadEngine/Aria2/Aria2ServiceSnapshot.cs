namespace XDM.DownloadEngine.Aria2;

public sealed record Aria2ServiceSnapshot(
    Aria2Health Health,
    IReadOnlyList<Aria2TaskSnapshot> Tasks,
    DateTimeOffset RefreshedAt,
    bool IsRefreshing)
{
    public static Aria2ServiceSnapshot Disabled { get; } = new(
        Aria2Health.Disabled,
        [],
        DateTimeOffset.MinValue,
        false);
}
