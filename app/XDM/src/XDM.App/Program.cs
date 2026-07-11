using Avalonia;
using Microsoft.Extensions.DependencyInjection;
using XDM.App.ViewModels;
using XDM.Core.Categories;
using XDM.Core.State;

namespace XDM.App;

internal static class Program
{
    [STAThread]
    public static int Main(string[] args)
    {
        if (args.Contains("--validate-bootstrap", StringComparer.Ordinal))
        {
            return ValidateBootstrap();
        }

        return BuildAvaloniaApp().StartWithClassicDesktopLifetime(args);
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
            && archiveCategory.MatchesFileName("release.zip");

        if (!valid)
        {
            Console.Error.WriteLine("XDM modern core bootstrap validation failed.");
            return 1;
        }

        Console.WriteLine(
            $"XDM modern core validated: {viewModel.Sections.Count} sections, {viewModel.CoreStatus}, {viewModel.RuntimeDescription}.");
        return 0;
    }
}
