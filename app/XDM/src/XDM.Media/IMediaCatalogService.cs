namespace XDM.Media;

public interface IMediaCatalogService
{
    Task<MediaCatalog> GetCatalogAsync(
        Uri source,
        MediaRequestMetadata? metadata = null,
        CancellationToken cancellationToken = default);
}
