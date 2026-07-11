namespace XDM.Core.Abstractions;

public interface IDialogService
{
    Task<string?> SelectFolderAsync(string? initialPath = null, CancellationToken cancellationToken = default);

    Task ShowErrorAsync(string title, string message, CancellationToken cancellationToken = default);

    Task<bool> ConfirmAsync(string title, string message, CancellationToken cancellationToken = default);
}
