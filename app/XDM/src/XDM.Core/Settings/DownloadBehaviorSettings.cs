namespace XDM.Core.Settings;

public sealed record DownloadBehaviorSettings(
    string DefaultDuplicateBehavior,
    bool CreateDestinationDirectory,
    bool AutoSelectCategory,
    bool RememberLastRequestMetadata)
{
    public static DownloadBehaviorSettings Default { get; } = new(
        "AutoRename",
        true,
        true,
        false);

    public DownloadBehaviorSettings Normalize()
    {
        string behavior = DefaultDuplicateBehavior is "Overwrite" or "Skip" or "AutoRename"
            ? DefaultDuplicateBehavior
            : "AutoRename";
        return this with { DefaultDuplicateBehavior = behavior };
    }
}
