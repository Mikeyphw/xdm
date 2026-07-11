namespace XDM.Diagnostics;

public interface IDiagnosticBundleService
{
    Task<string> ExportAsync(
        string destinationDirectory,
        CancellationToken cancellationToken = default);
}
