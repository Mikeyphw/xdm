using System.Net.Http.Headers;
using System.Xml;
using System.Xml.Linq;

namespace XDM.Media;

public sealed class MediaProbeService(HttpClient httpClient) : IMediaProbeService
{
    private static readonly HashSet<string> DirectMediaExtensions = new(
        [".mp4", ".mkv", ".webm", ".mov", ".avi", ".mp3", ".m4a", ".aac", ".flac", ".ogg", ".wav"],
        StringComparer.OrdinalIgnoreCase);

    public async Task<MediaProbeResult> ProbeAsync(Uri source, CancellationToken cancellationToken = default)
    {
        ArgumentNullException.ThrowIfNull(source);
        if (!source.IsAbsoluteUri || source.Scheme is not ("http" or "https"))
        {
            throw new ArgumentException("Media probing requires an absolute HTTP or HTTPS URL.", nameof(source));
        }

        string extension = Path.GetExtension(source.AbsolutePath);
        if (DirectMediaExtensions.Contains(extension))
        {
            return new MediaProbeResult(
                source,
                MediaKind.DirectFile,
                null,
                1,
                Path.GetFileName(source.LocalPath),
                "Direct media file");
        }

        using HttpRequestMessage request = new(HttpMethod.Get, source);
        using HttpResponseMessage response = await httpClient
            .SendAsync(request, HttpCompletionOption.ResponseHeadersRead, cancellationToken)
            .ConfigureAwait(false);
        response.EnsureSuccessStatusCode();

        string? contentType = response.Content.Headers.ContentType?.MediaType;
        string? fileName = ResolveFileName(response.Content.Headers.ContentDisposition, source);
        bool hls = extension.Equals(".m3u8", StringComparison.OrdinalIgnoreCase)
            || Contains(contentType, "mpegurl");
        bool dash = extension.Equals(".mpd", StringComparison.OrdinalIgnoreCase)
            || Contains(contentType, "dash+xml");

        if (hls)
        {
            string manifest = await response.Content.ReadAsStringAsync(cancellationToken).ConfigureAwait(false);
            int variants = manifest.Split('\n')
                .Count(static line => line.TrimStart().StartsWith("#EXT-X-STREAM-INF", StringComparison.OrdinalIgnoreCase));
            return new MediaProbeResult(
                source,
                MediaKind.Hls,
                contentType,
                Math.Max(1, variants),
                fileName,
                variants > 0 ? $"HLS master playlist with {variants} variants" : "HLS media playlist");
        }

        if (dash)
        {
            string manifest = await response.Content.ReadAsStringAsync(cancellationToken).ConfigureAwait(false);
            int representations = CountDashRepresentations(manifest);
            return new MediaProbeResult(
                source,
                MediaKind.Dash,
                contentType,
                Math.Max(1, representations),
                fileName,
                $"DASH manifest with {representations} representations");
        }

        if (contentType is not null
            && (contentType.StartsWith("video/", StringComparison.OrdinalIgnoreCase)
                || contentType.StartsWith("audio/", StringComparison.OrdinalIgnoreCase)))
        {
            return new MediaProbeResult(
                source,
                MediaKind.DirectFile,
                contentType,
                1,
                fileName,
                "Direct media response");
        }

        return new MediaProbeResult(source, MediaKind.Unknown, contentType, 0, fileName, "No supported media format detected");
    }

    private static int CountDashRepresentations(string manifest)
    {
        try
        {
            XmlReaderSettings settings = new()
            {
                DtdProcessing = DtdProcessing.Prohibit,
                XmlResolver = null
            };
            using StringReader textReader = new(manifest);
            using XmlReader xmlReader = XmlReader.Create(textReader, settings);
            XDocument document = XDocument.Load(xmlReader, LoadOptions.None);
            return document.Descendants()
                .Count(static element => string.Equals(element.Name.LocalName, "Representation", StringComparison.Ordinal));
        }
        catch (XmlException)
        {
            return 0;
        }
    }

    private static string? ResolveFileName(ContentDispositionHeaderValue? contentDisposition, Uri source)
    {
        string? candidate = contentDisposition?.FileNameStar ?? contentDisposition?.FileName;
        if (!string.IsNullOrWhiteSpace(candidate))
        {
            return candidate.Trim().Trim('"');
        }

        string pathName = Path.GetFileName(source.LocalPath);
        return string.IsNullOrWhiteSpace(pathName) ? null : pathName;
    }

    private static bool Contains(string? value, string fragment)
        => value?.Contains(fragment, StringComparison.OrdinalIgnoreCase) == true;
}
