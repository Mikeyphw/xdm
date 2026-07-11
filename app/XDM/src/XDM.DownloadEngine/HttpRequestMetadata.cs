using System.Net.Http.Headers;
using System.Text;

namespace XDM.DownloadEngine;

internal static class HttpRequestMetadata
{
    public static void Apply(
        HttpRequestMessage request,
        IReadOnlyDictionary<string, string>? headers,
        string? username,
        string? password,
        string? cookie,
        string? referer,
        string? userAgent)
    {
        ArgumentNullException.ThrowIfNull(request);
        if (headers is not null)
        {
            foreach ((string name, string value) in headers)
            {
                request.Headers.TryAddWithoutValidation(name, value);
            }
        }

        if (!string.IsNullOrWhiteSpace(username))
        {
            string credentials = Convert.ToBase64String(
                Encoding.UTF8.GetBytes($"{username}:{password ?? string.Empty}"));
            request.Headers.Authorization = new AuthenticationHeaderValue("Basic", credentials);
        }

        if (!string.IsNullOrWhiteSpace(cookie))
        {
            request.Headers.TryAddWithoutValidation("Cookie", cookie);
        }

        if (!string.IsNullOrWhiteSpace(referer))
        {
            request.Headers.TryAddWithoutValidation("Referer", referer);
        }

        if (!string.IsNullOrWhiteSpace(userAgent))
        {
            request.Headers.TryAddWithoutValidation("User-Agent", userAgent);
        }
    }
}
