using System.Diagnostics.CodeAnalysis;
using System.Net;
using System.Text.RegularExpressions;

namespace XDM.Platform;

public sealed partial class PacProxy : IWebProxy
{
    private const int MaximumScriptBytes = 1024 * 1024;
    private readonly PacRule[] _rules;
    private readonly string? _defaultDirective;

    public PacProxy(string script, ICredentials? credentials = null)
    {
        ArgumentNullException.ThrowIfNull(script);
        if (System.Text.Encoding.UTF8.GetByteCount(script) > MaximumScriptBytes)
        {
            throw new InvalidDataException("The PAC script exceeds 1 MiB.");
        }

        Credentials = credentials;
        (_rules, _defaultDirective) = Parse(script);
    }

    public ICredentials? Credentials { get; set; }

    public Uri GetProxy(Uri destination)
    {
        ArgumentNullException.ThrowIfNull(destination);
        foreach (PacRule rule in _rules)
        {
            if (rule.Matches(destination))
            {
                return ResolveDirective(rule.Directive, destination);
            }
        }

        return ResolveDirective(_defaultDirective, destination);
    }

    public bool IsBypassed(Uri host)
        => GetProxy(host) == host;

    public static async Task<PacProxy> LoadAsync(
        Uri scriptUri,
        ICredentials? credentials,
        TimeSpan timeout,
        CancellationToken cancellationToken = default)
    {
        ArgumentNullException.ThrowIfNull(scriptUri);
        if (!scriptUri.IsAbsoluteUri || scriptUri.Scheme is not ("http" or "https" or "file"))
        {
            throw new ArgumentException("PAC URLs must use HTTP, HTTPS, or file.", nameof(scriptUri));
        }

        string script;
        if (scriptUri.IsFile)
        {
            FileInfo info = new(scriptUri.LocalPath);
            if (!info.Exists)
            {
                throw new FileNotFoundException("The PAC file does not exist.", info.FullName);
            }
            if (info.Length > MaximumScriptBytes)
            {
                throw new InvalidDataException("The PAC script exceeds 1 MiB.");
            }
            script = await File.ReadAllTextAsync(info.FullName, cancellationToken).ConfigureAwait(false);
        }
        else
        {
            using SocketsHttpHandler handler = new()
            {
                UseProxy = false,
                AutomaticDecompression = DecompressionMethods.All,
                ConnectTimeout = timeout
            };
            using HttpClient client = new(handler) { Timeout = timeout };
            using HttpResponseMessage response = await client.GetAsync(
                scriptUri,
                HttpCompletionOption.ResponseHeadersRead,
                cancellationToken).ConfigureAwait(false);
            response.EnsureSuccessStatusCode();
            if (response.Content.Headers.ContentLength is > MaximumScriptBytes)
            {
                throw new InvalidDataException("The PAC script exceeds 1 MiB.");
            }
            await using Stream stream = await response.Content.ReadAsStreamAsync(cancellationToken)
                .ConfigureAwait(false);
            using MemoryStream buffer = new();
            byte[] block = new byte[16 * 1024];
            while (true)
            {
                int read = await stream.ReadAsync(block, cancellationToken).ConfigureAwait(false);
                if (read == 0)
                {
                    break;
                }

                if (buffer.Length + read > MaximumScriptBytes)
                {
                    throw new InvalidDataException("The PAC script exceeds 1 MiB.");
                }

                await buffer.WriteAsync(block.AsMemory(0, read), cancellationToken).ConfigureAwait(false);
            }

            script = System.Text.Encoding.UTF8.GetString(buffer.ToArray());
        }

        return new PacProxy(script, credentials);
    }

    internal static Uri ResolveDirective(string? directive, Uri destination)
    {
        if (string.IsNullOrWhiteSpace(directive))
        {
            return destination;
        }

        foreach (string item in directive.Split(';', StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries))
        {
            if (string.Equals(item, "DIRECT", StringComparison.OrdinalIgnoreCase))
            {
                return destination;
            }

            int separator = item.IndexOf(' ');
            if (separator <= 0)
            {
                continue;
            }

            string kind = item[..separator].Trim();
            string endpoint = item[(separator + 1)..].Trim();
            if (kind.Equals("PROXY", StringComparison.OrdinalIgnoreCase)
                || kind.Equals("HTTP", StringComparison.OrdinalIgnoreCase)
                || kind.Equals("HTTPS", StringComparison.OrdinalIgnoreCase))
            {
                if (TryCreateProxyUri(endpoint, out Uri? proxy))
                {
                    return proxy;
                }
            }
        }

        return destination;
    }

    private static bool TryCreateProxyUri(string endpoint, [NotNullWhen(true)] out Uri? proxy)
    {
        proxy = null;
        if (endpoint.Contains('\r')
            || endpoint.Contains('\n'))
        {
            return false;
        }

        if (!Uri.TryCreate($"http://{endpoint}", UriKind.Absolute, out Uri? candidate)
            || candidate.Port is <= 0 or > 65535
            || string.IsNullOrWhiteSpace(candidate.Host))
        {
            return false;
        }

        proxy = candidate;
        return true;
    }

    private static (PacRule[] Rules, string? DefaultDirective) Parse(string script)
    {
        List<PacRule> rules = [];
        foreach (Match match in ConditionalReturnRegex().Matches(script))
        {
            string condition = match.Groups[1].Value;
            string directive = match.Groups[2].Value;
            PacRule? rule = ParseCondition(condition, directive);
            if (rule is not null)
            {
                rules.Add(rule);
            }
        }

        MatchCollection returns = ReturnRegex().Matches(script);
        string? fallback = returns.Count > 0 ? returns[^1].Groups[1].Value : null;
        return ([.. rules], fallback);
    }

    private static PacRule? ParseCondition(string condition, string directive)
    {
        if (condition.Contains("isPlainHostName", StringComparison.Ordinal))
        {
            return new PacRule(PacRuleKind.PlainHost, string.Empty, directive);
        }

        Match domain = DnsDomainRegex().Match(condition);
        if (domain.Success)
        {
            return new PacRule(PacRuleKind.DomainSuffix, domain.Groups[1].Value, directive);
        }

        Match shell = ShellMatchRegex().Match(condition);
        if (shell.Success)
        {
            return new PacRule(PacRuleKind.ShellPattern, shell.Groups[1].Value, directive);
        }

        return null;
    }

    private sealed record PacRule(PacRuleKind Kind, string Pattern, string Directive)
    {
        public bool Matches(Uri destination)
            => Kind switch
            {
                PacRuleKind.PlainHost => !destination.Host.Contains('.'),
                PacRuleKind.DomainSuffix => destination.Host.EndsWith(Pattern, StringComparison.OrdinalIgnoreCase),
                PacRuleKind.ShellPattern => ShellMatches(destination.Host, Pattern),
                _ => false
            };

        private static bool ShellMatches(string value, string pattern)
        {
            string regex = "^" + Regex.Escape(pattern)
                .Replace("\\*", ".*", StringComparison.Ordinal)
                .Replace("\\?", ".", StringComparison.Ordinal) + "$";
            return Regex.IsMatch(value, regex, RegexOptions.IgnoreCase | RegexOptions.CultureInvariant, TimeSpan.FromMilliseconds(50));
        }
    }

    private enum PacRuleKind
    {
        PlainHost,
        DomainSuffix,
        ShellPattern
    }

    [GeneratedRegex(@"if\s*\((.*?)\)\s*\{?\s*return\s+[""']([^""']+)[""']\s*;", RegexOptions.IgnoreCase | RegexOptions.Singleline | RegexOptions.CultureInvariant, 100)]
    private static partial Regex ConditionalReturnRegex();

    [GeneratedRegex(@"return\s+[""']([^""']+)[""']\s*;", RegexOptions.IgnoreCase | RegexOptions.CultureInvariant, 100)]
    private static partial Regex ReturnRegex();

    [GeneratedRegex(@"dnsDomainIs\s*\(\s*host\s*,\s*[""']([^""']+)[""']\s*\)", RegexOptions.IgnoreCase | RegexOptions.CultureInvariant, 100)]
    private static partial Regex DnsDomainRegex();

    [GeneratedRegex(@"shExpMatch\s*\(\s*host\s*,\s*[""']([^""']+)[""']\s*\)", RegexOptions.IgnoreCase | RegexOptions.CultureInvariant, 100)]
    private static partial Regex ShellMatchRegex();
}
