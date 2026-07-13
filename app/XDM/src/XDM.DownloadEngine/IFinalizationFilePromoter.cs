namespace XDM.DownloadEngine;

public interface IFinalizationFilePromoter
{
    Task<FinalizationPromotionResult> PromoteAsync(
        string sourcePath,
        string destinationPath,
        FinalizationMarker marker,
        CancellationToken cancellationToken = default);
}
