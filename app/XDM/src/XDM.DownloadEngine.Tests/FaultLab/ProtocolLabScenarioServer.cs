using System.Collections.Concurrent;
using System.Globalization;
using System.Net;
using System.Text;

namespace XDM.DownloadEngine.Tests.FaultLab;

internal sealed class ProtocolLabScenarioServer : IDisposable, IAsyncDisposable
{
    private const long FiveTebibytes = 5L * 1024 * 1024 * 1024 * 1024;
    private const string StableEntityTag = "\"protocol-lab-v1\"";
    private const string ExpectedBasicAuthorization = "Basic dXNlcjpwYXNz";
    private readonly ConcurrentDictionary<string, int> _routeAttempts = new(StringComparer.Ordinal);
    private readonly DeterministicHttpFaultServer _server;
    private readonly byte[] _payload;

    public ProtocolLabScenarioServer(bool useTls = false, int payloadLength = 64 * 1024)
    {
        ArgumentOutOfRangeException.ThrowIfLessThan(payloadLength, 1024);
        _payload = Enumerable.Range(0, payloadLength)
            .Select(static value => (byte)(value % 251))
            .ToArray();
        _server = new DeterministicHttpFaultServer(CreateResponse, useTls);
    }

    public Uri BaseUri => _server.BaseUri;

    public byte[] Payload => _payload;

    public IReadOnlyList<FaultRequest> Requests => _server.Requests;

    public int RequestCount => _server.RequestCount;

    public Uri GetUri(string relativePath)
        => _server.GetUri(relativePath);

    public void Dispose()
    {
        _server.Dispose();
        GC.SuppressFinalize(this);
    }

    public async ValueTask DisposeAsync()
    {
        await _server.DisposeAsync().ConfigureAwait(false);
        GC.SuppressFinalize(this);
    }

    private FaultResponse CreateResponse(FaultRequest request, int _)
    {
        Uri target = ParseTarget(request.Target);
        string path = target.AbsolutePath;
        int routeAttempt = _routeAttempts.AddOrUpdate(path, 1, static (_, value) => value + 1);

        if (path.StartsWith("/redirect/", StringComparison.Ordinal))
        {
            string suffix = path[10..];
            int remaining = int.Parse(suffix, NumberStyles.None, CultureInfo.InvariantCulture);
            return remaining > 0
                ? FaultResponse.Redirect($"/redirect/{remaining - 1}")
                : FaultResponse.Redirect("/payload");
        }

        if (path.StartsWith("/expiring/", StringComparison.Ordinal))
        {
            return CreateExpiringResponse(path, routeAttempt);
        }

        return path switch
        {
            "/payload" => FaultResponse.Ok(_payload, StableEntityTag),
            "/range/valid" => CreateValidRangeResponse(request),
            "/range/invalid" => CreateInvalidRangeResponse(request),
            "/range/ignored" => FaultResponse.Ok(_payload, StableEntityTag),
            "/etag/changing" => FaultResponse.Ok(
                _payload,
                routeAttempt == 1 ? "\"protocol-lab-v1\"" : "\"protocol-lab-v2\""),
            "/interrupt" => CreateInterruptedResponse(request, routeAttempt),
            "/chunked" => CreateChunkedResponse(),
            "/length/short" => FaultResponse.Ok(
                _payload,
                StableEntityTag,
                declaredContentLength: _payload.Length,
                bodyBytesToWrite: _payload.Length / 2),
            "/auth/basic" => CreateAuthenticationResponse(request),
            "/rate-limit" => CreateRateLimitResponse(routeAttempt),
            "/large" => CreateLargeLogicalResponse(request),
            "/filename/malformed" => CreateMalformedFilenameResponse(),
            "/header/malformed" => CreateMalformedHeaderResponse(),
            "/media/hls/master.m3u8" => FaultResponse.Text(CreateHlsManifest(), "application/vnd.apple.mpegurl"),
            "/media/hls/segment.ts" => CreateMediaSegmentResponse("video/mp2t"),
            "/media/dash/manifest.mpd" => FaultResponse.Text(CreateDashManifest(), "application/dash+xml"),
            "/media/dash/segment.m4s" => CreateMediaSegmentResponse("video/iso.segment"),
            _ => new FaultResponse(404, [], EmptyHeaders, 0, 0)
        };
    }

    private FaultResponse CreateValidRangeResponse(FaultRequest request)
    {
        if (request.RangeStart is not long start)
        {
            return FaultResponse.Ok(_payload, StableEntityTag);
        }

        if (start >= _payload.Length)
        {
            return FaultResponse.RangeNotSatisfiable(_payload.Length, StableEntityTag);
        }

        int offset = checked((int)start);
        return FaultResponse.Partial(
            _payload[offset..],
            start,
            _payload.Length - 1,
            _payload.Length,
            StableEntityTag);
    }

    private FaultResponse CreateInvalidRangeResponse(FaultRequest request)
    {
        long requested = request.RangeStart ?? 0;
        int offset = checked((int)Math.Min(requested, _payload.Length - 1L));
        return FaultResponse.Partial(
            _payload[offset..],
            requested + 1,
            _payload.Length,
            _payload.Length + 1L,
            StableEntityTag);
    }

    private FaultResponse CreateExpiringResponse(string path, int routeAttempt)
    {
        string token = path[10..];
        return string.Equals(token, "valid", StringComparison.Ordinal) && routeAttempt == 1
            ? FaultResponse.Ok(_payload, StableEntityTag)
            : new FaultResponse(403, [], EmptyHeaders, 0, 0);
    }

    private FaultResponse CreateInterruptedResponse(FaultRequest request, int routeAttempt)
    {
        if (routeAttempt == 1)
        {
            return FaultResponse.Ok(
                _payload,
                StableEntityTag,
                declaredContentLength: _payload.Length,
                bodyBytesToWrite: _payload.Length / 2);
        }

        return CreateValidRangeResponse(request);
    }

    private FaultResponse CreateChunkedResponse()
        => new(
            200,
            _payload,
            new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase)
            {
                ["ETag"] = StableEntityTag
            },
            UseChunkedEncoding: true,
            ChunkSize: 257,
            OmitContentLength: true);

    private FaultResponse CreateAuthenticationResponse(FaultRequest request)
    {
        if (string.Equals(request.Authorization, ExpectedBasicAuthorization, StringComparison.Ordinal))
        {
            return FaultResponse.Ok(_payload, StableEntityTag);
        }

        return new FaultResponse(
            401,
            [],
            new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase)
            {
                ["WWW-Authenticate"] = "Basic realm=\"XDM Protocol Lab\""
            },
            0,
            0);
    }

    private FaultResponse CreateRateLimitResponse(int routeAttempt)
        => routeAttempt == 1
            ? new FaultResponse(
                429,
                [],
                new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase)
                {
                    ["Retry-After"] = "0"
                },
                0,
                0)
            : FaultResponse.Ok(_payload, StableEntityTag);

    private FaultResponse CreateLargeLogicalResponse(FaultRequest request)
    {
        long start = request.RangeStart ?? FiveTebibytes - 1;
        return FaultResponse.Partial(
            [_payload[0]],
            start,
            start,
            FiveTebibytes,
            StableEntityTag);
    }

    private FaultResponse CreateMalformedFilenameResponse()
        => new(
            200,
            _payload,
            new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase)
            {
                ["Content-Disposition"] = "attachment; filename=\"../CON?.bin\"; filename*=UTF-8''bad%ZZname.bin",
                ["Content-Type"] = "application/octet-stream"
            });

    private FaultResponse CreateMalformedHeaderResponse()
        => new(
            200,
            _payload,
            EmptyHeaders,
            AdditionalRawHeaderLines: ["Header-Without-A-Colon"]);

    private FaultResponse CreateMediaSegmentResponse(string contentType)
        => new(
            200,
            _payload[..1024],
            new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase)
            {
                ["Content-Type"] = contentType
            });

    private string CreateHlsManifest()
        => $"""
            #EXTM3U
            #EXT-X-VERSION:7
            #EXT-X-TARGETDURATION:4
            #EXT-X-MEDIA-SEQUENCE:0
            #EXTINF:4.0,
            {new Uri(BaseUri, "media/hls/segment.ts")}
            #EXT-X-ENDLIST
            """;

    private string CreateDashManifest()
        => $"""
            <?xml version="1.0" encoding="UTF-8"?>
            <MPD xmlns="urn:mpeg:dash:schema:mpd:2011" type="static" mediaPresentationDuration="PT4S">
              <Period duration="PT4S">
                <AdaptationSet mimeType="video/mp4">
                  <Representation id="video" bandwidth="800000" width="1280" height="720">
                    <BaseURL>{new Uri(BaseUri, "media/dash/segment.m4s")}</BaseURL>
                    <SegmentBase />
                  </Representation>
                </AdaptationSet>
              </Period>
            </MPD>
            """;

    private static Uri ParseTarget(string target)
        => Uri.TryCreate(target, UriKind.Absolute, out Uri? absolute)
            ? absolute
            : new Uri(new Uri("http://protocol-lab.invalid/", UriKind.Absolute), target);

    private static Dictionary<string, string> EmptyHeaders { get; }
        = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);
}
