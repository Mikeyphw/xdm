using Avalonia;
using Microsoft.Extensions.DependencyInjection;
using XDM.App.ViewModels;
using XDM.Core.Categories;
using XDM.Core.State;
using XDM.Diagnostics;
using XDM.DownloadEngine;
using XDM.Platform;

namespace XDM.App;

internal static class Program
{
    private const int ActivationPort = 49614;

    [STAThread]
    public static int Main(string[] args)
    {
        if (args.Contains("--validate-bootstrap", StringComparer.Ordinal))
        {
            return ValidateBootstrap();
        }

        App.LaunchOptions = StartupOptions.Parse(args);
        using SingleInstanceCoordinator coordinator = new("xdm-modern", ActivationPort);
        if (!coordinator.TryAcquire())
        {
            return coordinator.SignalPrimaryAsync().GetAwaiter().GetResult() ? 0 : 2;
        }

        App.InstanceCoordinator = coordinator;
        try
        {
            return BuildAvaloniaApp().StartWithClassicDesktopLifetime(args);
        }
        finally
        {
            App.InstanceCoordinator = null;
        }
    }

    public static AppBuilder BuildAvaloniaApp()
        => AppBuilder.Configure<App>()
            .UsePlatformDetect()
#if DEBUG
            .WithDeveloperTools()
#endif
            .WithInterFont()
            .LogToTrace();

    private static int ValidateBootstrap()
    {
        using ServiceProvider services = App.ConfigureServices();
        MainWindowViewModel viewModel = services.GetRequiredService<MainWindowViewModel>();
        IApplicationState state = services.GetRequiredService<IApplicationState>();
        IDownloadManager downloadManager = services.GetRequiredService<IDownloadManager>();

        DownloadCategory archiveCategory = new(
            "archives",
            "Archives",
            ["zip", ".7z", "tar.zst"],
            Path.GetTempPath(),
            isPredefined: true);

        bool valid = viewModel.Sections.Count == 6
            && viewModel.SelectedSection is not null
            && state.Current.CoreReady
            && viewModel.CoreStatus == "Ready"
            && archiveCategory.MatchesFileName("release.zip")
            && downloadManager is DownloadManager;

        if (!valid)
        {
            Console.Error.WriteLine("XDM functional downloader bootstrap validation failed.");
            return 1;
        }

        Console.WriteLine(
            $"XDM downloader validated: {viewModel.Sections.Count} sections, {viewModel.CoreStatus}, {viewModel.RuntimeDescription}.");
        return 0;
    }
}
