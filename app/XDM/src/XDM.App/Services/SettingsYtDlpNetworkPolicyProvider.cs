using XDM.Core.Settings;
using XDM.Media;

namespace XDM.App.Services;

public sealed class SettingsYtDlpNetworkPolicyProvider(ISettingsService settingsService) : IYtDlpNetworkPolicyProvider
{
    public YtDlpNetworkPolicy Current
    {
        get
        {
            NetworkSettings network = (settingsService.Current.Network ?? NetworkSettings.Default).Normalize();
            ProxySettings proxy = network.Proxy ?? ProxySettings.SystemDefault;
            return proxy.Mode switch
            {
                ProxyMode.None => YtDlpNetworkPolicy.Direct,
                ProxyMode.Manual when !string.IsNullOrWhiteSpace(proxy.Host)
                    => new YtDlpNetworkPolicy(BuildManualProxyUri(proxy), false),
                _ => YtDlpNetworkPolicy.SystemDefault
            };
        }
    }

    private static string BuildManualProxyUri(ProxySettings proxy)
    {
        Uri uri;
        if (Uri.TryCreate(proxy.Host, UriKind.Absolute, out Uri? absolute)
            && absolute.Scheme is "http" or "https" or "socks5")
        {
            UriBuilder absoluteBuilder = new(absolute) { Port = proxy.Port };
            uri = absoluteBuilder.Uri;
        }
        else
        {
            uri = new UriBuilder(Uri.UriSchemeHttp, proxy.Host!, proxy.Port).Uri;
        }

        UriBuilder builder = new(uri);
        if (proxy.AuthenticationMode == ProxyAuthenticationMode.Basic
            && !string.IsNullOrWhiteSpace(proxy.Username))
        {
            builder.UserName = Uri.EscapeDataString(proxy.Username);
            builder.Password = Uri.EscapeDataString(proxy.Password ?? string.Empty);
        }

        return builder.Uri.AbsoluteUri;
    }
}
