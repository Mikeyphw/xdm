using System.Net;
using System.Net.Http.Headers;

namespace XDM.BrowserMedia.Tests;

internal sealed class RoutingHandler(Func<HttpRequestMessage, HttpResponseMessage> route) : HttpMessageHandler
{
    protected override Task<HttpResponseMessage> SendAsync(
        HttpRequestMessage request,
        CancellationToken cancellationToken)
    {
        cancellationToken.ThrowIfCancellationRequested();
        return Task.FromResult(route(request));
    }

    public static HttpResponseMessage Text(string value, string contentType)
    {
        StringContent content = new(value);
        content.Headers.ContentType = MediaTypeHeaderValue.Parse(contentType);
        return new HttpResponseMessage(HttpStatusCode.OK) { Content = content };
    }

    public static HttpResponseMessage Bytes(byte[] value)
        => new(HttpStatusCode.OK) { Content = new ByteArrayContent(value) };
}
