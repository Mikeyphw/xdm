using System.Text;
using System.Text.Json;

namespace XDM.BrowserIntegration;

public sealed record FirefoxExtensionHandoff(
    string RawUri,
    string Action,
    IReadOnlyList<BrowserCaptureRequest> Requests,
    string? PageTitle = null,
    string? CaptureSessionId = null,
    int DeclaredCandidateCount = 0,
    bool Truncated = false);

public static class FirefoxExtensionHandoffParser
{
    public const string ReleaseScheme = "xdmdownload";
    public const string DebugScheme = "xdmdownload-debug";
    public const string CanonicalExtensionId = "xdm-android-media-bridge@mikeyphw";
    public const int MaximumDeepLinkBytes = 64 * 1024;
    public const int MaximumDirectCandidates = 24;
    public const int MaximumCandidatesJsonCharacters = 52 * 1024;

    private static readonly HashSet<string> SupportedSchemes = new(StringComparer.OrdinalIgnoreCase)
    {
        ReleaseScheme,
        DebugScheme
    };

    private static readonly HashSet<string> ForwardedHeaders = new(StringComparer.OrdinalIgnoreCase)
    {
        "Accept",
        "Accept-Language",
        "Origin"
    };

    public static string? FindHandoffArgument(IEnumerable<string> arguments)
    {
        ArgumentNullException.ThrowIfNull(arguments);
        return arguments.FirstOrDefault(IsHandoffUri);
    }

    public static bool IsHandoffUri(string? value)
    {
        if (string.IsNullOrWhiteSpace(value) || Encoding.UTF8.GetByteCount(value) > MaximumDeepLinkBytes)
        {
            return false;
        }

        return Uri.TryCreate(value.Trim(), UriKind.Absolute, out Uri? uri)
            && SupportedSchemes.Contains(uri.Scheme);
    }

    public static bool TryParse(string? rawUri, out FirefoxExtensionHandoff? handoff, out string error)
    {
        handoff = null;
        error = string.Empty;
        if (string.IsNullOrWhiteSpace(rawUri))
        {
            error = "Firefox handoff is empty.";
            return false;
        }

        string raw = rawUri.Trim();
        if (Encoding.UTF8.GetByteCount(raw) > MaximumDeepLinkBytes)
        {
            error = "Firefox handoff exceeds the 64 KiB contract limit.";
            return false;
        }

        if (!Uri.TryCreate(raw, UriKind.Absolute, out Uri? uri)
            || !SupportedSchemes.Contains(uri.Scheme)
            || !string.IsNullOrEmpty(uri.UserInfo)
            || !string.IsNullOrEmpty(uri.Fragment)
            || (!string.IsNullOrEmpty(uri.AbsolutePath) && uri.AbsolutePath != "/"))
        {
            error = "Firefox handoff envelope is invalid.";
            return false;
        }

        string action = uri.Host.ToLowerInvariant();
        if (action is not ("add" or "capture"))
        {
            error = "Firefox handoff action is unsupported.";
            return false;
        }

        if (!TryParseQuery(uri.Query, out Dictionary<string, string> parameters, out error))
        {
            return false;
        }

        if (!parameters.TryGetValue("v", out string? versionRaw)
            || !int.TryParse(versionRaw, System.Globalization.NumberStyles.None, System.Globalization.CultureInfo.InvariantCulture, out int version)
            || action == "add" && version != 1
            || action == "capture" && version != 3)
        {
            error = "Firefox handoff contract version is unsupported.";
            return false;
        }

        if (!TryParseHttpUri(parameters.GetValueOrDefault("url"), out Uri? primaryUrl))
        {
            error = "Firefox handoff media URL is invalid.";
            return false;
        }

        Uri? pageUrl = ParseOptionalHttpUri(parameters.GetValueOrDefault("page"));
        string? pageTitle = SanitizeText(parameters.GetValueOrDefault("title"), 240);
        string? fileName = SanitizeFileName(parameters.GetValueOrDefault("filename"));
        string? mimeType = SanitizeMimeType(parameters.GetValueOrDefault("mime"));
        long? contentLength = ParsePositiveLong(parameters.GetValueOrDefault("length"));
        string? requestId = NormalizeIdentity(
            parameters.GetValueOrDefault("stableMediaId")
            ?? parameters.GetValueOrDefault("pageObservationNonce"));

        BrowserCaptureRequest primary;
        try
        {
            primary = CreateRequest(
                primaryUrl!,
                requestId,
                fileName,
                mimeType,
                contentLength,
                pageUrl,
                action,
                parameters.GetValueOrDefault("headers"),
                parameters.GetValueOrDefault("finalHeaders"));
        }
        catch (InvalidDataException exception)
        {
            error = $"Firefox handoff request was rejected: {exception.Message}";
            return false;
        }

        List<BrowserCaptureRequest> requests = [primary];
        string? captureSessionId = NormalizeIdentity(parameters.GetValueOrDefault("sid"), 96);
        int declaredCount = ParseBoundedInt(parameters.GetValueOrDefault("candidateCount"), 1, 160) ?? 0;
        bool truncated = IsTrue(parameters.GetValueOrDefault("truncated"));

        if (action == "capture"
            && parameters.TryGetValue("candidates", out string? candidatesJson)
            && candidatesJson.Length <= MaximumCandidatesJsonCharacters)
        {
            if (captureSessionId is null)
            {
                error = "Firefox capture-session candidates require a valid session ID.";
                return false;
            }

            foreach (BrowserCaptureRequest candidate in ParseCandidates(candidatesJson, pageUrl, pageTitle))
            {
                if (requests.Any(existing => SameCandidate(existing, candidate)))
                {
                    continue;
                }

                requests.Add(candidate);
                if (requests.Count >= MaximumDirectCandidates)
                {
                    break;
                }
            }
        }

        handoff = new FirefoxExtensionHandoff(
            raw,
            action,
            requests,
            pageTitle,
            captureSessionId,
            declaredCount,
            truncated);
        return true;
    }

    private static bool SameCandidate(BrowserCaptureRequest left, BrowserCaptureRequest right)
        => Uri.Compare(left.Url, right.Url, UriComponents.AbsoluteUri, UriFormat.SafeUnescaped, StringComparison.OrdinalIgnoreCase) == 0
            && string.Equals(left.RequestId, right.RequestId, StringComparison.Ordinal);

    private static BrowserCaptureRequest CreateRequest(
        Uri url,
        string? requestId,
        string? fileName,
        string? mimeType,
        long? contentLength,
        Uri? pageUrl,
        string action,
        string? rawHeaders,
        string? finalHeaders)
    {
        ParsedHeaders headers = ParseHeaders(!string.IsNullOrWhiteSpace(finalHeaders) ? finalHeaders : rawHeaders);
        BrowserCaptureRequest request = new(
            url,
            RequestId: requestId,
            FileName: fileName,
            Headers: headers.Forwarded.Count == 0 ? null : headers.Forwarded,
            Cookie: headers.Cookie,
            Referer: headers.Referer ?? pageUrl?.AbsoluteUri,
            UserAgent: headers.UserAgent,
            Browser: "Firefox",
            MimeType: mimeType,
            FileSize: contentLength,
            Method: "GET",
            SourcePage: pageUrl?.AbsoluteUri,
            Operation: action == "capture" ? "media" : "context",
            BypassRules: true);
        BrowserCaptureRequest normalized = request.Normalize();
        normalized.Validate();
        return normalized;
    }

    private static IEnumerable<BrowserCaptureRequest> ParseCandidates(string json, Uri? fallbackPageUrl, string? fallbackTitle)
    {
        JsonDocument document;
        try
        {
            document = JsonDocument.Parse(json, new JsonDocumentOptions { MaxDepth = 16 });
        }
        catch (JsonException)
        {
            yield break;
        }

        using (document)
        {
            if (document.RootElement.ValueKind != JsonValueKind.Array)
            {
                yield break;
            }

            int count = 0;
            foreach (JsonElement candidate in document.RootElement.EnumerateArray())
            {
                if (++count > MaximumDirectCandidates || candidate.ValueKind != JsonValueKind.Object)
                {
                    yield break;
                }

                if (!TryParseHttpUri(GetString(candidate, "url"), out Uri? url))
                {
                    continue;
                }

                Uri? pageUrl = ParseOptionalHttpUri(GetString(candidate, "pageUrl")) ?? fallbackPageUrl;
                string? title = SanitizeText(GetString(candidate, "title"), 240) ?? fallbackTitle;
                string? fileName = SanitizeFileName(title);
                string? mimeType = SanitizeMimeType(GetString(candidate, "contentType"));
                long? contentLength = GetPositiveInt64(candidate, "contentLength");
                string? requestId = NormalizeIdentity(GetString(candidate, "stableMediaId") ?? GetString(candidate, "requestId"));
                string? finalHeaders = GetHeaderObject(candidate, "finalHeaders");

                BrowserCaptureRequest request;
                try
                {
                    request = CreateRequest(
                        url!,
                        requestId,
                        fileName,
                        mimeType,
                        contentLength,
                        pageUrl,
                        "capture",
                        finalHeaders,
                        finalHeaders);
                }
                catch (InvalidDataException)
                {
                    continue;
                }

                yield return request;
            }
        }
    }

    private static string? GetString(JsonElement element, string name)
        => element.TryGetProperty(name, out JsonElement value) && value.ValueKind == JsonValueKind.String
            ? value.GetString()
            : null;

    private static long? GetPositiveInt64(JsonElement element, string name)
    {
        if (!element.TryGetProperty(name, out JsonElement value))
        {
            return null;
        }

        long parsed = value.ValueKind == JsonValueKind.Number && value.TryGetInt64(out long number)
            ? number
            : value.ValueKind == JsonValueKind.String
                && long.TryParse(value.GetString(), System.Globalization.NumberStyles.None, System.Globalization.CultureInfo.InvariantCulture, out long textNumber)
                    ? textNumber
                    : 0;
        return parsed > 0 ? parsed : null;
    }

    private static string? GetHeaderObject(JsonElement element, string name)
    {
        if (!element.TryGetProperty(name, out JsonElement value) || value.ValueKind != JsonValueKind.Object)
        {
            return null;
        }

        List<string> lines = [];
        foreach (JsonProperty property in value.EnumerateObject().Take(24))
        {
            if (property.Value.ValueKind != JsonValueKind.String)
            {
                continue;
            }

            string headerValue = property.Value.GetString() ?? string.Empty;
            lines.Add($"{property.Name}: {headerValue}");
        }

        return lines.Count == 0 ? null : string.Join('\n', lines);
    }

    private static ParsedHeaders ParseHeaders(string? block)
    {
        Dictionary<string, string> forwarded = new(StringComparer.OrdinalIgnoreCase);
        string? cookie = null;
        string? referer = null;
        string? userAgent = null;
        if (string.IsNullOrWhiteSpace(block))
        {
            return new ParsedHeaders(forwarded, cookie, referer, userAgent);
        }

        foreach (string rawLine in block.Split('\n').Take(24))
        {
            string line = rawLine.Replace("\r", string.Empty, StringComparison.Ordinal);
            int separator = line.IndexOf(':');
            if (separator <= 0)
            {
                continue;
            }

            string name = line[..separator].Trim();
            string value = SanitizeHeaderValue(line[(separator + 1)..]);
            if (value.Length == 0)
            {
                continue;
            }

            if (name.Equals("cookie", StringComparison.OrdinalIgnoreCase))
            {
                cookie = value;
            }
            else if (name.Equals("referer", StringComparison.OrdinalIgnoreCase))
            {
                referer = ParseOptionalHttpUri(value)?.AbsoluteUri;
            }
            else if (name.Equals("user-agent", StringComparison.OrdinalIgnoreCase))
            {
                userAgent = value;
            }
            else if (ForwardedHeaders.Contains(name))
            {
                forwarded[name] = value;
            }
            // Authorization and Range are intentionally not promoted to desktop runtime authority.
            // The canonical extension still sends its complete final evidence unchanged; desktop keeps
            // its existing least-privilege request policy while consuming the same extension package.
        }

        return new ParsedHeaders(forwarded, cookie, referer, userAgent);
    }

    private static string SanitizeHeaderValue(string value)
        => new string(value.Where(static character => character >= ' ' && character != '\u007f').Take(8 * 1024).ToArray()).Trim();

    private static string? SanitizeText(string? value, int limit)
    {
        if (string.IsNullOrWhiteSpace(value))
        {
            return null;
        }

        string clean = new string(value.Where(static character => !char.IsControl(character)).Take(limit).ToArray()).Trim();
        return clean.Length == 0 ? null : clean;
    }

    private static string? SanitizeFileName(string? value)
    {
        string? clean = SanitizeText(value, 320);
        if (clean is null)
        {
            return null;
        }

        clean = clean.Replace('\\', '/');
        int separator = clean.LastIndexOf('/');
        string leaf = (separator >= 0 ? clean[(separator + 1)..] : clean).Trim();
        return leaf.Length == 0 ? null : leaf[..Math.Min(160, leaf.Length)];
    }

    private static string? SanitizeMimeType(string? value)
    {
        string? clean = SanitizeText(value?.Split(';', 2)[0], 120)?.ToLowerInvariant();
        return clean is not null && clean.Contains('/', StringComparison.Ordinal) ? clean : null;
    }

    private static string? NormalizeIdentity(string? value, int limit = 128)
    {
        if (string.IsNullOrWhiteSpace(value))
        {
            return null;
        }

        string normalized = new(value.Trim()
            .Where(static character => char.IsAsciiLetterOrDigit(character) || character is '-' or '_' or '.')
            .Take(limit)
            .ToArray());
        return normalized.Length < 8 ? null : normalized;
    }

    private static bool TryParseQuery(string query, out Dictionary<string, string> parameters, out string error)
    {
        parameters = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);
        error = string.Empty;
        string rawQuery = query.StartsWith('?') ? query[1..] : query;
        if (rawQuery.Length == 0)
        {
            return true;
        }

        foreach (string part in rawQuery.Split('&', StringSplitOptions.RemoveEmptyEntries))
        {
            int separator = part.IndexOf('=');
            string encodedName = separator < 0 ? part : part[..separator];
            string encodedValue = separator < 0 ? string.Empty : part[(separator + 1)..];
            string name;
            string value;
            try
            {
                name = Uri.UnescapeDataString(encodedName.Replace('+', ' '));
                value = Uri.UnescapeDataString(encodedValue.Replace('+', ' '));
            }
            catch (UriFormatException)
            {
                error = "Firefox handoff query is malformed.";
                return false;
            }

            if (name.Length == 0 || parameters.ContainsKey(name))
            {
                error = "Firefox handoff query contains a missing or duplicate parameter.";
                return false;
            }

            parameters[name] = value;
        }

        return true;
    }

    private static bool TryParseHttpUri(string? value, out Uri? uri)
    {
        uri = null;
        if (string.IsNullOrWhiteSpace(value)
            || !Uri.TryCreate(value.Trim(), UriKind.Absolute, out Uri? parsed)
            || parsed.Scheme is not ("http" or "https")
            || !string.IsNullOrEmpty(parsed.UserInfo)
            || string.IsNullOrWhiteSpace(parsed.Host)
            || parsed.AbsoluteUri.Length > BrowserCaptureRequest.MaximumUrlLength)
        {
            return false;
        }

        uri = parsed;
        return true;
    }

    private static Uri? ParseOptionalHttpUri(string? value)
        => TryParseHttpUri(value, out Uri? uri) ? uri : null;

    private static long? ParsePositiveLong(string? value)
        => long.TryParse(value, System.Globalization.NumberStyles.None, System.Globalization.CultureInfo.InvariantCulture, out long parsed) && parsed > 0
            ? parsed
            : null;

    private static int? ParseBoundedInt(string? value, int minimum, int maximum)
        => int.TryParse(value, System.Globalization.NumberStyles.None, System.Globalization.CultureInfo.InvariantCulture, out int parsed)
            && parsed >= minimum
            && parsed <= maximum
                ? parsed
                : null;

    private static bool IsTrue(string? value)
        => value is "1" || string.Equals(value, "true", StringComparison.OrdinalIgnoreCase);

    private sealed record ParsedHeaders(
        IReadOnlyDictionary<string, string> Forwarded,
        string? Cookie,
        string? Referer,
        string? UserAgent);
}
