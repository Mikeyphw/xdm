using XDM.Core.Product;

namespace XDM.Core.Downloads;

public sealed record DownloadUrlToken(string Input, Uri? Uri, bool Accepted, string? RejectionReason = null);

public sealed record DownloadUrlParseResult(
    IReadOnlyList<Uri> AcceptedUrls,
    IReadOnlyList<DownloadUrlToken> Tokens)
{
    public IReadOnlyList<DownloadUrlToken> RejectedTokens => Tokens.Where(static token => !token.Accepted).ToArray();
}

public static class DownloadInputParser
{
    public static IReadOnlyList<Uri> ParseUrls(string? input)
        => ParseUrlsDetailed(input).AcceptedUrls;

    public static DownloadUrlParseResult ParseUrlsDetailed(string? input)
    {
        if (string.IsNullOrWhiteSpace(input))
        {
            return new([], []);
        }

        string[] values = input.Split(
            ['\r', '\n', ' ', '\t'],
            StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries);
        List<Uri> accepted = [];
        List<DownloadUrlToken> tokens = [];
        HashSet<string> identities = new(StringComparer.Ordinal);

        foreach (string value in values)
        {
            if (!Uri.TryCreate(value, UriKind.Absolute, out Uri? uri))
            {
                tokens.Add(new(value, null, false, "Invalid URL."));
                continue;
            }

            if (!ModernFeaturePolicy.IsSupportedDownloadUri(uri))
            {
                tokens.Add(new(value, uri, false, "Unsupported download protocol."));
                continue;
            }

            string identity = DownloadMetadata.NormalizeSourceIdentity(uri);
            if (!identities.Add(identity))
            {
                // Exact normalized duplicates are intentionally collapsed. Keep token provenance
                // so partial batch retry can explain what happened without destroying input.
                tokens.Add(new(value, uri, true, "Duplicate input token."));
                continue;
            }

            accepted.Add(uri);
            tokens.Add(new(value, uri, true));
        }

        return new(accepted, tokens);
    }

    public static IReadOnlyDictionary<string, string> ParseHeaders(string? input)
    {
        Dictionary<string, string> headers = new(StringComparer.OrdinalIgnoreCase);
        if (string.IsNullOrWhiteSpace(input))
        {
            return headers;
        }

        foreach (string line in input.Split(['\r', '\n'], StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries))
        {
            int separator = line.IndexOf(':', StringComparison.Ordinal);
            if (separator <= 0 || separator == line.Length - 1)
            {
                continue;
            }

            string name = line[..separator].Trim();
            string value = line[(separator + 1)..].Trim();
            if (name.Length > 0 && value.Length > 0)
            {
                headers[name] = value;
            }
        }

        return headers;
    }
}
