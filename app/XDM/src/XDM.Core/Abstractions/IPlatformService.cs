namespace XDM.Core.Abstractions;

public interface IPlatformService
{
    Task OpenFileAsync(string path, CancellationToken cancellationToken = default);

    Task RevealFileAsync(string path, CancellationToken cancellationToken = default);

    Task OpenUriAsync(Uri uri, CancellationToken cancellationToken = default);
}
