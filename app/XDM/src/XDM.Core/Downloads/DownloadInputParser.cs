namespace XDM.Core.Downloads;

public static class DownloadInputParser
{
    public static IReadOnlyList<Uri> ParseUrls(string? input)
    {
        if (string.IsNullOrWhiteSpace(input))
        {
            return [];
        }

        return input
            .Split(['\r', '\n', ' ', '\t'], StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries)
            .Select(static value => Uri.TryCreate(value, UriKind.Absolute, out Uri? uri) ? uri : null)
            .Where(static uri => uri is not null && uri.Scheme is "http" or "https")
            .Cast<Uri>()
            .DistinctBy(static uri => uri.AbsoluteUri, StringComparer.OrdinalIgnoreCase)
            .ToArray();
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
