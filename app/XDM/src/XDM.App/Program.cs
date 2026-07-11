using Avalonia;
using XDM.App.ViewModels;

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
        MainWindowViewModel viewModel = new();

        if (viewModel.Sections.Count != 6 || viewModel.SelectedSection is null)
        {
            Console.Error.WriteLine("XDM Avalonia bootstrap validation failed.");
            return 1;
        }

        Console.WriteLine($"XDM Avalonia bootstrap validated: {viewModel.Sections.Count} navigation sections.");
        return 0;
    }
}
