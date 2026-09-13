using System.Net;
using XDM.Core.Settings;

namespace XDM.Platform;

public static class ConfiguredHttpClientFactory
{
    public static HttpClient Create(ISettingsService settingsService)
    {
        ArgumentNullException.ThrowIfNull(settingsService);
        if (!settingsService.IsOperational)
        {
            return new HttpClient(new SettingsUnavailableHandler(settingsService), disposeHandler: true)
            {
                Timeout = Timeout.InfiniteTimeSpan
            };
        }

        return Create(settingsService.Current);
    }

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

        try
        {
            switch (proxy.Mode)
            {
                case ProxyMode.None:
                    handler.UseProxy = false;
                    break;
                case ProxyMode.Manual:
                    handler.UseProxy = true;
                    handler.Proxy = CreateManualProxy(proxy);
                    break;
                case ProxyMode.AutomaticScript:
                    handler.UseProxy = true;
                    handler.Proxy = LoadPacProxy(proxy, network.ConnectTimeoutSeconds);
                    break;
                default:
                    handler.UseProxy = true;
                    handler.Proxy = null;
                    handler.DefaultProxyCredentials = ResolveCredentials(proxy);
                    break;
            }
        }
        catch (Exception exception) when (exception is IOException
            or UnauthorizedAccessException
            or HttpRequestException
            or InvalidOperationException
            or UriFormatException
            or TaskCanceledException)
        {
            handler.Dispose();
            return CreateProxyConfigurationFailureClient(exception.Message);
        }

        return new HttpClient(handler, disposeHandler: true)
        {
            Timeout = network.RequestTimeoutSeconds == 0
                ? Timeout.InfiniteTimeSpan
                : TimeSpan.FromSeconds(network.RequestTimeoutSeconds)
        };
    }

    internal static ICredentials? ResolveCredentials(ProxySettings proxy)
        => proxy.AuthenticationMode switch
        {
            ProxyAuthenticationMode.Integrated => CredentialCache.DefaultNetworkCredentials,
            ProxyAuthenticationMode.Basic when !string.IsNullOrWhiteSpace(proxy.Username)
                => new NetworkCredential(proxy.Username, proxy.Password ?? string.Empty),
            _ => null
        };

    private static WebProxy CreateManualProxy(ProxySettings proxy)
    {
        WebProxy webProxy = new(BuildProxyUri(proxy))
        {
            BypassProxyOnLocal = proxy.BypassLocal,
            BypassList = proxy.BypassList?.ToArray() ?? [],
            Credentials = ResolveCredentials(proxy)
        };
        return webProxy;
    }

    private static PacProxy LoadPacProxy(ProxySettings proxy, int connectTimeoutSeconds)
    {
        Uri scriptUri = new(proxy.AutomaticConfigurationUrl
            ?? throw new InvalidOperationException("Automatic proxy mode requires a PAC URL."));
        return PacProxy.LoadAsync(
            scriptUri,
            ResolveCredentials(proxy),
            TimeSpan.FromSeconds(connectTimeoutSeconds),
            bypassLocal: proxy.BypassLocal,
            bypassList: proxy.BypassList).GetAwaiter().GetResult();
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
    private static HttpClient CreateProxyConfigurationFailureClient(string message)
        => new(new ProxyConfigurationFailureHandler(message), disposeHandler: true)
        {
            Timeout = Timeout.InfiniteTimeSpan
        };

    private sealed class ProxyConfigurationFailureHandler(string message) : HttpMessageHandler
    {
        protected override Task<HttpResponseMessage> SendAsync(
            HttpRequestMessage request,
            CancellationToken cancellationToken)
            => Task.FromException<HttpResponseMessage>(new InvalidOperationException(
                $"Network access is disabled because the configured proxy policy could not be initialized: {message}"));
    }

    private sealed class SettingsUnavailableHandler(ISettingsService settingsService) : HttpMessageHandler
    {
        protected override Task<HttpResponseMessage> SendAsync(
            HttpRequestMessage request,
            CancellationToken cancellationToken)
            => Task.FromException<HttpResponseMessage>(new InvalidOperationException(
                settingsService.LoadFailureMessage is { Length: > 0 } message
                    ? $"Network access is disabled because saved settings could not be loaded: {message} Save valid settings and restart XDM before starting transfers."
                    : "Network access is disabled because saved settings could not be loaded. Save valid settings and restart XDM before starting transfers."));
    }
}
