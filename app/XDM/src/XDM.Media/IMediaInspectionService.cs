namespace XDM.Media;

internal interface IMediaInspectionService
{
    Task<MediaInspection> InspectAsync(
        string sourcePath,
        CancellationToken cancellationToken = default);
}
