namespace XDM.BrowserIntegration;

public sealed record BrowserCaptureRequest(
    Uri Url,
    string? FileName = null,
    IReadOnlyDictionary<string, string>? Headers = null,
    string? Cookie = null,
    string? Referer = null,
    string? UserAgent = null,
    string? Browser = null,
    string? QueueId = null,
    string? CategoryId = null)
{
    public BrowserCaptureRequest Normalize()
        => this with
        {
            FileName = NormalizeOptional(FileName),
            Cookie = NormalizeOptional(Cookie),
            Referer = NormalizeOptional(Referer),
            UserAgent = NormalizeOptional(UserAgent),
            Browser = NormalizeOptional(Browser),
            QueueId = NormalizeOptional(QueueId),
            CategoryId = NormalizeOptional(CategoryId),
            Headers = Headers?
                .Where(static pair => !string.IsNullOrWhiteSpace(pair.Key))
                .GroupBy(static pair => pair.Key.Trim(), StringComparer.OrdinalIgnoreCase)
                .ToDictionary(
                    static group => group.Key,
                    static group => group.Last().Value?.Trim() ?? string.Empty,
                    StringComparer.OrdinalIgnoreCase)
        };

    public void Validate()
    {
        if (Url is null || !Url.IsAbsoluteUri || Url.Scheme is not ("http" or "https"))
        {
            throw new InvalidDataException("Browser capture only accepts absolute HTTP and HTTPS URLs.");
        }
    }

    private static string? NormalizeOptional(string? value)
        => string.IsNullOrWhiteSpace(value) ? null : value.Trim();
}
