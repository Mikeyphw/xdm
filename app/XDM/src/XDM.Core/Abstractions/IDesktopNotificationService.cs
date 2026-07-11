namespace XDM.Core.Abstractions;

public interface IDesktopNotificationService
{
    Task ShowAsync(string title, string message, CancellationToken cancellationToken = default);
}
