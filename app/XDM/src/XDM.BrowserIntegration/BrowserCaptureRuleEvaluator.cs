namespace XDM.BrowserIntegration;

public sealed record BrowserCaptureRuleDecision(bool Accepted, string Reason)
{
    public static BrowserCaptureRuleDecision Accept(string reason = "accepted") => new(true, reason);
    public static BrowserCaptureRuleDecision Reject(string reason) => new(false, reason);
}

public static class BrowserCaptureRuleEvaluator
{
    public static BrowserCaptureRuleDecision Evaluate(
        BrowserCaptureRequest request,
        BrowserCaptureRules? rules,
        DateTimeOffset? now = null)
    {
        ArgumentNullException.ThrowIfNull(request);
        BrowserCaptureRules normalized = (rules ?? new BrowserCaptureRules()).Normalize();
        DateTimeOffset timestamp = now ?? DateTimeOffset.UtcNow;

        if (!normalized.Enabled)
        {
            return BrowserCaptureRuleDecision.Reject("capture_disabled");
        }

        if (normalized.DisabledUntilUtc is DateTimeOffset disabledUntil && disabledUntil > timestamp)
        {
            return BrowserCaptureRuleDecision.Reject("temporarily_disabled");
        }

        if (request.IsIncognito && !normalized.CaptureIncognito)
        {
            return BrowserCaptureRuleDecision.Reject("incognito_disabled");
        }

        if (request.BypassRules && request.Operation is "context" or "download-all" or "media")
        {
            return BrowserCaptureRuleDecision.Accept("manual_capture");
        }

        string host = request.Url.IdnHost.ToLowerInvariant();
        if (MatchesSite(host, normalized.ExcludedSites))
        {
            return BrowserCaptureRuleDecision.Reject("site_excluded");
        }

        if (normalized.IncludedSites is { Count: > 0 } && !MatchesSite(host, normalized.IncludedSites))
        {
            return BrowserCaptureRuleDecision.Reject("site_not_included");
        }

        if (request.FileSize is long fileSize && fileSize < normalized.MinimumSizeBytes)
        {
            return BrowserCaptureRuleDecision.Reject("below_minimum_size");
        }

        string? mime = request.MimeType?.Split(';', 2)[0].Trim().ToLowerInvariant();
        if (mime is not null && MatchesMime(mime, normalized.BlockedMimeTypes))
        {
            return BrowserCaptureRuleDecision.Reject("mime_blocked");
        }

        if (normalized.AllowedMimeTypes is { Count: > 0 }
            && (mime is null || !MatchesMime(mime, normalized.AllowedMimeTypes)))
        {
            return BrowserCaptureRuleDecision.Reject("mime_not_allowed");
        }

        string? extension = ResolveExtension(request);
        if (extension is not null && normalized.BlockedExtensions!.Contains(extension, StringComparer.OrdinalIgnoreCase))
        {
            return BrowserCaptureRuleDecision.Reject("extension_blocked");
        }

        if (normalized.AllowedExtensions is { Count: > 0 }
            && (extension is null || !normalized.AllowedExtensions.Contains(extension, StringComparer.OrdinalIgnoreCase)))
        {
            return BrowserCaptureRuleDecision.Reject("extension_not_allowed");
        }

        return BrowserCaptureRuleDecision.Accept();
    }

    private static bool MatchesSite(string host, IReadOnlyList<string>? patterns)
    {
        if (patterns is null)
        {
            return false;
        }

        foreach (string rawPattern in patterns)
        {
            string pattern = rawPattern.Trim().TrimStart('*').TrimStart('.').ToLowerInvariant();
            if (pattern.Length == 0)
            {
                continue;
            }

            if (string.Equals(host, pattern, StringComparison.OrdinalIgnoreCase)
                || host.EndsWith($".{pattern}", StringComparison.OrdinalIgnoreCase))
            {
                return true;
            }
        }

        return false;
    }

    private static bool MatchesMime(string mime, IReadOnlyList<string>? patterns)
    {
        if (patterns is null)
        {
            return false;
        }

        foreach (string pattern in patterns)
        {
            if (pattern.EndsWith("/*", StringComparison.Ordinal)
                && mime.StartsWith(pattern[..^1], StringComparison.OrdinalIgnoreCase))
            {
                return true;
            }

            if (string.Equals(mime, pattern, StringComparison.OrdinalIgnoreCase))
            {
                return true;
            }
        }

        return false;
    }

    private static string? ResolveExtension(BrowserCaptureRequest request)
    {
        string source = request.FileName ?? request.Url.AbsolutePath;
        string extension = Path.GetExtension(source).TrimStart('.').ToLowerInvariant();
        return extension.Length == 0 ? null : extension;
    }
}
