using XDM.Core.Product;

namespace XDM.Core.Settings;

public sealed record UpdateSettings(
    UpdateChannel Channel,
    bool AutomaticChecks,
    bool NotifyWhenStaged)
{
    public static UpdateSettings Default { get; } = new(UpdateChannel.Stable, true, true);

    public UpdateSettings Normalize()
        => Enum.IsDefined(Channel) ? this : Default;
}
