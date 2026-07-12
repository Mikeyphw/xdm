using Avalonia;
using Avalonia.Controls.ApplicationLifetimes;
using Avalonia.Threading;
using XDM.Core.Abstractions;

namespace XDM.App.Services;

public sealed class AvaloniaApplicationLifetimeService : IApplicationLifetimeService
{
    public Task RequestShutdownAsync(CancellationToken cancellationToken = default)
        => DispatchAsync(() =>
        {
            App.ExitRequested = true;
            if (Application.Current?.ApplicationLifetime is IClassicDesktopStyleApplicationLifetime desktop)
            {
                desktop.Shutdown();
            }
        }, cancellationToken);

    public Task ActivateMainWindowAsync(CancellationToken cancellationToken = default)
        => DispatchAsync(() =>
        {
            if (Application.Current?.ApplicationLifetime is IClassicDesktopStyleApplicationLifetime
                { MainWindow: MainWindow window })
            {
                window.RestoreAndActivate();
            }
        }, cancellationToken);

    private static Task DispatchAsync(Action action, CancellationToken cancellationToken)
    {
        ArgumentNullException.ThrowIfNull(action);
        cancellationToken.ThrowIfCancellationRequested();
        if (Dispatcher.UIThread.CheckAccess())
        {
            action();
            return Task.CompletedTask;
        }

        return Dispatcher.UIThread.InvokeAsync(() =>
        {
            action();
            return Task.CompletedTask;
        });
    }
}
