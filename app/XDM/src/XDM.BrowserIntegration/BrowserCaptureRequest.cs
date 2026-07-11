using System.Net.Http.Headers;
using System.Text;

namespace XDM.BrowserIntegration;

public sealed record BrowserCaptureRequest(
    Uri Url,
    string? RequestId = null,
    string? FileName = null,
    IReadOnlyDictionary<string, string>? Headers = null,
    string? Cookie = null,
    string? Referer = null,
    string? UserAgent = null,
    string? Browser = null,
    string? QueueId = null,
    string? CategoryId = null,
    string? MimeType = null,
    long? FileSize = null,
    string Method = "GET",
    string? RequestBodyBase64 = null,
    string? RequestBodyContentType = null,
    string? SourcePage = null,
    string Operation = "automatic",
    bool IsIncognito = false,
    bool BypassRules = false)
{
    public const int MaximumHeaderCount = 64;
    public const int MaximumHeaderNameLength = 128;
    public const int MaximumHeaderValueLength = 8 * 1024;
    public const int MaximumRequestBodyBytes = 16 * 1024;
    public const int MaximumUrlLength = 16 * 1024;

    private static readonly HashSet<string> AllowedHeaders = new(StringComparer.OrdinalIgnoreCase)
    {
        "Accept",
        "Accept-Language",
        "Cache-Control",
        "DNT",
        "Origin",
        "Pragma",
        "X-Requested-With"
    };

    private static readonly HashSet<string> ForbiddenHeaders = new(StringComparer.OrdinalIgnoreCase)
    {
        "Connection",
        "Content-Length",
        "Host",
        "Keep-Alive",
        "Proxy-Authenticate",
        "Proxy-Authorization",
        "TE",
        "Trailer",
        "Transfer-Encoding",
        "Upgrade"
    };

    public BrowserCaptureRequest Normalize()
    {
        IReadOnlyDictionary<string, string>? normalizedHeaders = Headers?
            .Where(static pair => !string.IsNullOrWhiteSpace(pair.Key))
            .GroupBy(static pair => pair.Key.Trim(), StringComparer.OrdinalIgnoreCase)
            .ToDictionary(
                static group => group.Key,
                static group => group.Last().Value?.Trim() ?? string.Empty,
                StringComparer.OrdinalIgnoreCase);

        return this with
        {
            RequestId = NormalizeOptional(RequestId),
            FileName = NormalizeOptional(FileName),
            Cookie = NormalizeOptional(Cookie),
            Referer = NormalizeOptional(Referer),
            UserAgent = NormalizeOptional(UserAgent),
            Browser = NormalizeOptional(Browser),
            QueueId = NormalizeOptional(QueueId),
            CategoryId = NormalizeOptional(CategoryId),
            MimeType = NormalizeOptional(MimeType)?.ToLowerInvariant(),
            Method = string.IsNullOrWhiteSpace(Method) ? "GET" : Method.Trim().ToUpperInvariant(),
            RequestBodyBase64 = NormalizeOptional(RequestBodyBase64),
            RequestBodyContentType = NormalizeOptional(RequestBodyContentType),
            SourcePage = NormalizeOptional(SourcePage),
            Operation = string.IsNullOrWhiteSpace(Operation) ? "automatic" : Operation.Trim().ToLowerInvariant(),
            Headers = normalizedHeaders
        };
    }

    public void Validate()
    {
        if (Url is null || !Url.IsAbsoluteUri || Url.Scheme is not ("http" or "https"))
        {
            throw new InvalidDataException("Browser capture only accepts absolute HTTP and HTTPS URLs.");
        }

        if (Url.AbsoluteUri.Length > MaximumUrlLength)
        {
            throw new InvalidDataException("Browser capture URL exceeds the maximum supported length.");
        }

        if (RequestId is { Length: > 128 }
            || RequestId is not null
                && RequestId.Any(static character => !(char.IsAsciiLetterOrDigit(character) || character is '-' or '_' or '.')))
        {
            throw new InvalidDataException("Browser capture request ID is invalid.");
        }

        if (FileName is { Length: > 1024 }
            || FileName is not null && FileName.Any(static character => char.IsControl(character)))
        {
            throw new InvalidDataException("Browser capture filename is invalid or too long.");
        }

        if (Browser is { Length: > 128 }
            || QueueId is { Length: > 128 }
            || CategoryId is { Length: > 128 }
            || ContainsControlCharacters(Browser)
            || ContainsControlCharacters(QueueId)
            || ContainsControlCharacters(CategoryId))
        {
            throw new InvalidDataException("Browser capture routing metadata is invalid or too long.");
        }

        if (FileSize is < 0)
        {
            throw new InvalidDataException("Browser capture file size cannot be negative.");
        }

        if (Method is not ("GET" or "POST"))
        {
            throw new InvalidDataException("Browser capture only accepts GET and POST requests.");
        }

        if (Operation is not ("automatic" or "context" or "download-all" or "media"))
        {
            throw new InvalidDataException("Browser capture operation is invalid.");
        }

        if (Headers is { Count: > MaximumHeaderCount })
        {
            throw new InvalidDataException("Browser capture contains too many request headers.");
        }

        if (Headers is not null)
        {
            foreach ((string name, string value) in Headers)
            {
                ValidateHeader(name, value);
            }
        }

        if (Cookie is { Length: > MaximumHeaderValueLength }
            || Referer is { Length: > MaximumUrlLength }
            || UserAgent is { Length: > MaximumHeaderValueLength }
            || MimeType is { Length: > 512 }
            || RequestBodyContentType is { Length: > 512 }
            || SourcePage is { Length: > MaximumUrlLength }
            || ContainsControlCharacters(Cookie)
            || ContainsControlCharacters(UserAgent)
            || ContainsControlCharacters(MimeType)
            || ContainsControlCharacters(RequestBodyContentType))
        {
            throw new InvalidDataException("Browser capture metadata is invalid or exceeds the maximum supported length.");
        }

        if (RequestBodyContentType is not null
            && !MediaTypeHeaderValue.TryParse(RequestBodyContentType, out _))
        {
            throw new InvalidDataException("Browser request body content type is invalid.");
        }

        if (Referer is not null && (!Uri.TryCreate(Referer, UriKind.Absolute, out Uri? refererUri)
            || refererUri.Scheme is not ("http" or "https")))
        {
            throw new InvalidDataException("Browser capture referer must be an absolute HTTP or HTTPS URL.");
        }

        if (SourcePage is not null && (!Uri.TryCreate(SourcePage, UriKind.Absolute, out Uri? sourcePageUri)
            || sourcePageUri.Scheme is not ("http" or "https")))
        {
            throw new InvalidDataException("Browser capture source page must be an absolute HTTP or HTTPS URL.");
        }

        if (RequestBodyBase64 is not null)
        {
            if (Method != "POST")
            {
                throw new InvalidDataException("Browser request bodies are only accepted for POST captures.");
            }

            byte[] body;
            try
            {
                body = Convert.FromBase64String(RequestBodyBase64);
            }
            catch (FormatException exception)
            {
                throw new InvalidDataException("Browser request body is not valid base64.", exception);
            }

            if (body.Length > MaximumRequestBodyBytes)
            {
                throw new InvalidDataException("Browser request body exceeds 16 KiB.");
            }
        }
    }

    public byte[]? GetRequestBody()
        => RequestBodyBase64 is null ? null : Convert.FromBase64String(RequestBodyBase64);

    private static void ValidateHeader(string name, string value)
    {
        if (name.Length is 0 or > MaximumHeaderNameLength
            || value.Length > MaximumHeaderValueLength
            || name.Any(static character => character <= 32 || character >= 127 || character == ':')
            || value.Contains('\r')
            || value.Contains('\n'))
        {
            throw new InvalidDataException("Browser capture contains an invalid request header.");
        }

        if (ForbiddenHeaders.Contains(name) || !AllowedHeaders.Contains(name))
        {
            throw new InvalidDataException($"Browser capture header '{name}' is not allowed.");
        }

        _ = Encoding.ASCII.GetByteCount(name);
    }

    private static bool ContainsControlCharacters(string? value)
        => value?.Any(static character => character is '\r' or '\n' || char.IsControl(character)) == true;

    private static string? NormalizeOptional(string? value)
        => string.IsNullOrWhiteSpace(value) ? null : value.Trim();
}
