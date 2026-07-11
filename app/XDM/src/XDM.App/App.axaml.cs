using System.Net;
using Avalonia;
using Avalonia.Controls.ApplicationLifetimes;
using Avalonia.Markup.Xaml;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.Logging;
using XDM.App.Services;
using XDM.App.ViewModels;
using XDM.BrowserIntegration;
using XDM.Core.Abstractions;
using XDM.Core.Persistence;
using XDM.Core.Settings;
using XDM.Core.State;
using XDM.DownloadEngine;
using XDM.DownloadEngine.Queues;
using XDM.Media;
using XDM.Persistence;
using XDM.Platform;

namespace XDM.App;

public partial class App : Application
{
    private ServiceProvider? _services;

    public override void Initialize()
    {
        AvaloniaXamlLoader.Load(this);
    }

    public override void OnFrameworkInitializationCompleted()
    {
        _services = ConfigureServices();
        _services.GetRequiredService<ISettingsService>()
            .InitializeAsync()
            .GetAwaiter()
            .GetResult();
        _services.GetRequiredService<IDownloadManager>()
            .InitializeAsync()
            .GetAwaiter()
            .GetResult();
        _services.GetRequiredService<IQueueSchedulerRuntime>()
            .InitializeAsync()
            .GetAwaiter()
            .GetResult();
        _services.GetRequiredService<IBrowserIntegrationService>()
            .InitializeAsync()
            .GetAwaiter()
            .GetResult();

        if (ApplicationLifetime is IClassicDesktopStyleApplicationLifetime desktop)
        {
            desktop.MainWindow = _services.GetRequiredService<MainWindow>();
            desktop.Exit += (_, _) => _services.Dispose();
        }

        base.OnFrameworkInitializationCompleted();
    }

    internal static ServiceProvider ConfigureServices()
    {
        ServiceCollection services = new();

        services.AddLogging(builder =>
        {
            builder.ClearProviders();
            builder.AddConsole();
            builder.SetMinimumLevel(LogLevel.Information);
        });

        services.AddSingleton(new HttpClient(new SocketsHttpHandler
        {
            AutomaticDecompression = DecompressionMethods.All,
            AllowAutoRedirect = true,
            MaxAutomaticRedirections = 10,
            ConnectTimeout = TimeSpan.FromSeconds(30)
        })
        {
            Timeout = Timeout.InfiniteTimeSpan
        });

        services.AddSingleton<IBrowserIntegrationService, LoopbackBrowserIntegrationService>();
        services.AddSingleton<IMediaProbeService, MediaProbeService>();
        services.AddSingleton<IApplicationState, ApplicationState>();
        services.AddSingleton<IDownloadHistoryStore, JsonDownloadHistoryStore>();
        services.AddSingleton<ISettingsStore, JsonSettingsStore>();
        services.AddSingleton<ISettingsService, SettingsService>();
        services.AddSingleton<IDownloadManager, DownloadManager>();
        services.AddSingleton<IQueueSchedulerRuntime, QueueSchedulerRuntime>();
        services.AddSingleton<IPlatformInfo, PlatformInfo>();
        services.AddSingleton<IUiDispatcher, AvaloniaUiDispatcher>();
        services.AddSingleton<MainWindowViewModel>();
        services.AddSingleton<MainWindow>();

        return services.BuildServiceProvider(new ServiceProviderOptions
        {
            ValidateOnBuild = true,
            ValidateScopes = true
        });
    }
}
