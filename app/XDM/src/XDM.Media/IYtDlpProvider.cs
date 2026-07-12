namespace XDM.Media;

public interface IYtDlpProvider
{
    Task<ExternalToolHealth> GetHealthAsync(CancellationToken cancellationToken = default);

    Task<MediaCatalog?> TryGetCatalogAsync(
        Uri source,
        MediaRequestMetadata metadata,
        CancellationToken cancellationToken = default);
}
