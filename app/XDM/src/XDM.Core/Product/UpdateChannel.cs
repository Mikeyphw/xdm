namespace XDM.Core.Product;

public enum UpdateChannel
{
    Stable,
    Beta,
    Nightly
}

public static class UpdateChannelExtensions
{
    public static string ToManifestName(this UpdateChannel channel)
        => channel.ToString().ToLowerInvariant();

    public static bool TryParse(string? value, out UpdateChannel channel)
        => Enum.TryParse(value, ignoreCase: true, out channel);
}
