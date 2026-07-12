namespace XDM.Core.Settings;

public sealed record SettingsImportResult(
    ApplicationSettings Settings,
    string SourceFormat,
    IReadOnlyList<string> Warnings,
    int ImportedCategoryCount,
    int ImportedQueueCount,
    int ImportedCredentialCount);

public interface ISettingsTransferService
{
    Task ExportAsync(
        string path,
        ApplicationSettings settings,
        bool includeSecrets,
        CancellationToken cancellationToken = default);

    Task<SettingsImportResult> ImportAsync(
        string path,
        ApplicationSettings baseline,
        CancellationToken cancellationToken = default);
}
