using System.Net;
using XDM.Core.Settings;

namespace XDM.Platform;

public static class ConfiguredHttpClientFactory
{
    public static HttpClient Create(ApplicationSettings settings)
    {
        ArgumentNullException.ThrowIfNull(settings);
        NetworkSettings network = (settings.Network ?? NetworkSettings.Default).Normalize();
        ProxySettings proxy = network.Proxy!;
        SocketsHttpHandler handler = new()
        {
            AutomaticDecompression = DecompressionMethods.All,
            AllowAutoRedirect = true,
            MaxAutomaticRedirections = 10,
            ConnectTimeout = TimeSpan.FromSeconds(network.ConnectTimeoutSeconds),
            PooledConnectionLifetime = TimeSpan.FromMinutes(10)
        };

        switch (proxy.Mode)
        {
            case ProxyMode.None:
                handler.UseProxy = false;
                break;
            case ProxyMode.Manual:
                WebProxy webProxy = new(BuildProxyUri(proxy))
                {
                    BypassProxyOnLocal = proxy.BypassLocal,
                    BypassList = proxy.BypassList?.ToArray() ?? []
                };
                if (!string.IsNullOrWhiteSpace(proxy.Username))
                {
                    webProxy.Credentials = new NetworkCredential(proxy.Username, proxy.Password ?? string.Empty);
                }
                handler.UseProxy = true;
                handler.Proxy = webProxy;
                break;
            default:
                handler.UseProxy = true;
                handler.Proxy = null;
                handler.DefaultProxyCredentials = CredentialCache.DefaultCredentials;
                break;
        }

        return new HttpClient(handler, disposeHandler: true)
        {
            Timeout = network.RequestTimeoutSeconds == 0
                ? Timeout.InfiniteTimeSpan
                : TimeSpan.FromSeconds(network.RequestTimeoutSeconds)
        };
    }

    private static Uri BuildProxyUri(ProxySettings proxy)
    {
        string host = proxy.Host ?? throw new InvalidOperationException("Manual proxy mode requires a host.");
        if (Uri.TryCreate(host, UriKind.Absolute, out Uri? absolute)
            && absolute.Scheme is "http" or "https" or "socks5")
        {
            UriBuilder builder = new(absolute) { Port = proxy.Port };
            return builder.Uri;
        }
        return new UriBuilder(Uri.UriSchemeHttp, host, proxy.Port).Uri;
    }
}
