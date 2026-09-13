namespace XDM.Media;

public sealed class StaticYtDlpNetworkPolicyProvider(YtDlpNetworkPolicy policy) : IYtDlpNetworkPolicyProvider
{
    public static StaticYtDlpNetworkPolicyProvider SystemDefault { get; } = new(YtDlpNetworkPolicy.SystemDefault);

    public YtDlpNetworkPolicy Current { get; } = policy;
}
