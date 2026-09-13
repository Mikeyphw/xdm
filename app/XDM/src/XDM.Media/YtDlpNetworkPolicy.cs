namespace XDM.Media;

public sealed record YtDlpNetworkPolicy(string? ProxyUri, bool ForceDirect)
{
    public static YtDlpNetworkPolicy SystemDefault { get; } = new(null, false);

    public static YtDlpNetworkPolicy Direct { get; } = new(string.Empty, true);
}
