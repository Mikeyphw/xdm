namespace XDM.Media;

public sealed class MediaProbeService : IMediaProbeService
{
    private readonly MediaCatalogService _catalogService;

    public MediaProbeService(HttpClient httpClient)
    {
        ArgumentNullException.ThrowIfNull(httpClient);
        _catalogService = new MediaCatalogService(httpClient, NoYtDlpProvider.Instance);
    }

    public async Task<MediaProbeResult> ProbeAsync(Uri source, CancellationToken cancellationToken = default)
    {
        MediaCatalog catalog = await _catalogService
            .GetCatalogAsync(source, MediaRequestMetadata.Empty, cancellationToken)
            .ConfigureAwait(false);
        return new MediaProbeResult(
            source,
            catalog.Kind,
            null,
            catalog.Formats.Count,
            catalog.Title,
            catalog.Description);
    }

    private sealed class NoYtDlpProvider : IYtDlpProvider
    {
        public static NoYtDlpProvider Instance { get; } = new();

        public Task<ExternalToolHealth> GetHealthAsync(CancellationToken cancellationToken = default)
            => Task.FromResult(new ExternalToolHealth("yt-dlp", false, null, null, "Disabled for lightweight probes."));

        public Task<MediaCatalog?> TryGetCatalogAsync(
            Uri source,
            MediaRequestMetadata metadata,
            CancellationToken cancellationToken = default)
            => Task.FromResult<MediaCatalog?>(null);
    }
}
