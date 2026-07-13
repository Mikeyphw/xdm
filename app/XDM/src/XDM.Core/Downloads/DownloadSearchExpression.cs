using System.Globalization;

namespace XDM.Core.Downloads;

public sealed record DownloadSearchDocument(
    string FileName,
    Uri Source,
    string DestinationPath,
    DownloadState State,
    string QueueId,
    string? CategoryId,
    IReadOnlyList<string> Tags,
    long? TotalBytes,
    bool IsArchived,
    bool IsMissing,
    bool IsDuplicate);

public static class DownloadSearchExpression
{
    public static bool Matches(string? query, DownloadSearchDocument document)
    {
        ArgumentNullException.ThrowIfNull(document);
        if (string.IsNullOrWhiteSpace(query))
        {
            return true;
        }

        foreach (string token in Tokenize(query))
        {
            int separator = token.IndexOf(':');
            if (separator <= 0)
            {
                if (!MatchesPlainText(token, document))
                {
                    return false;
                }
                continue;
            }

            string key = token[..separator].ToLowerInvariant();
            string value = token[(separator + 1)..].Trim('"');
            bool matches = key switch
            {
                "status" => MatchesStatus(value, document.State),
                "tag" => document.Tags.Contains(value, StringComparer.OrdinalIgnoreCase),
                "site" => document.Source.Host.Equals(value, StringComparison.OrdinalIgnoreCase)
                    || document.Source.Host.EndsWith($".{value}", StringComparison.OrdinalIgnoreCase),
                "queue" => document.QueueId.Contains(value, StringComparison.OrdinalIgnoreCase),
                "category" => document.CategoryId?.Contains(value, StringComparison.OrdinalIgnoreCase) == true,
                "archived" => MatchesBoolean(value, document.IsArchived),
                "missing" => MatchesBoolean(value, document.IsMissing),
                "duplicate" => MatchesBoolean(value, document.IsDuplicate),
                "size" => MatchesSize(value, document.TotalBytes),
                _ => MatchesPlainText(token, document)
            };
            if (!matches)
            {
                return false;
            }
        }

        return true;
    }

    private static IEnumerable<string> Tokenize(string query)
    {
        bool quoted = false;
        int start = 0;
        for (int index = 0; index <= query.Length; index++)
        {
            if (index < query.Length && query[index] == '"')
            {
                quoted = !quoted;
            }
            if (index == query.Length || (!quoted && char.IsWhiteSpace(query[index])))
            {
                if (index > start)
                {
                    yield return query[start..index];
                }
                start = index + 1;
            }
        }
    }

    private static bool MatchesPlainText(string token, DownloadSearchDocument document)
        => document.FileName.Contains(token, StringComparison.OrdinalIgnoreCase)
            || document.Source.AbsoluteUri.Contains(token, StringComparison.OrdinalIgnoreCase)
            || document.DestinationPath.Contains(token, StringComparison.OrdinalIgnoreCase)
            || document.QueueId.Contains(token, StringComparison.OrdinalIgnoreCase)
            || document.Tags.Any(tag => tag.Contains(token, StringComparison.OrdinalIgnoreCase));

    private static bool MatchesStatus(string value, DownloadState state)
        => value.Equals("active", StringComparison.OrdinalIgnoreCase)
            ? state is DownloadState.Queued or DownloadState.Connecting or DownloadState.Downloading or DownloadState.Finalizing
            : value.Equals(state.ToString(), StringComparison.OrdinalIgnoreCase);

    private static bool MatchesBoolean(string value, bool actual)
        => bool.TryParse(value, out bool expected) && expected == actual;

    private static bool MatchesSize(string value, long? totalBytes)
    {
        if (totalBytes is null)
        {
            return false;
        }

        string comparison = value.StartsWith(">=", StringComparison.Ordinal) || value.StartsWith("<=", StringComparison.Ordinal)
            ? value[..2]
            : value.StartsWith('>') || value.StartsWith('<') || value.StartsWith('=')
                ? value[..1]
                : "=";
        int amountStart = comparison == "=" && !value.StartsWith('=') ? 0 : comparison.Length;
        string amount = value[amountStart..];
        if (!TryParseBytes(amount, out long bytes))
        {
            return false;
        }

        return comparison switch
        {
            ">" => totalBytes > bytes,
            ">=" => totalBytes >= bytes,
            "<" => totalBytes < bytes,
            "<=" => totalBytes <= bytes,
            _ => totalBytes == bytes
        };
    }

    private static bool TryParseBytes(string value, out long bytes)
    {
        value = value.Trim();
        string suffix = new(value.SkipWhile(static character => char.IsDigit(character) || character is '.' or ',').ToArray());
        string numeric = value[..^suffix.Length].Replace(',', '.');
        if (!double.TryParse(numeric, NumberStyles.Float, CultureInfo.InvariantCulture, out double number) || number < 0)
        {
            bytes = 0;
            return false;
        }

        double multiplier = suffix.Trim().ToUpperInvariant() switch
        {
            "KB" or "KIB" => 1024d,
            "MB" or "MIB" => 1024d * 1024d,
            "GB" or "GIB" => 1024d * 1024d * 1024d,
            "TB" or "TIB" => 1024d * 1024d * 1024d * 1024d,
            "" or "B" => 1d,
            _ => -1d
        };
        if (multiplier < 0 || number > long.MaxValue / multiplier)
        {
            bytes = 0;
            return false;
        }

        bytes = (long)(number * multiplier);
        return true;
    }
}
