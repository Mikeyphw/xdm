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
using XDM.Diagnostics;
using XDM.DownloadEngine;
using XDM.DownloadEngine.Queues;
using XDM.Media;
using XDM.Persistence;
using XDM.Platform;

namespace XDM.App;

public partial class App : Application
{
    internal static StartupOptions LaunchOptions { get; set; } = StartupOptions.Default;

    public override void Initialize()
    {
        AvaloniaXamlLoader.Load(this);
    }

    public override void OnFrameworkInitializationCompleted()
    {
        ServiceProvider services = ConfigureServices();
        IRecoveryService recovery = services.GetRequiredService<IRecoveryService>();
        IDiagnosticEventStore diagnostics = services.GetRequiredService<IDiagnosticEventStore>();
        recovery.Initialize(LaunchOptions);
        diagnostics.Record(
            DiagnosticSeverity.Information,
            "XDM-STARTUP-001",
            "Application services created.",
            new Dictionary<string, string?>
            {
                ["safeMode"] = recovery.SafeMode ? "true" : "false",
                ["uncleanPreviousSession"] = recovery.PreviousSessionWasUnclean ? "true" : "false"
            });

        InitializeService(
            "XDM-STARTUP-SETTINGS",
            () => services.GetRequiredService<ISettingsService>().InitializeAsync(),
            diagnostics);
        InitializeService(
            "XDM-STARTUP-DOWNLOADS",
            () => services.GetRequiredService<IDownloadManager>().InitializeAsync(),
            diagnostics);

        if (!recovery.SafeMode)
        {
            InitializeService(
                "XDM-STARTUP-SCHEDULER",
                () => services.GetRequiredService<IQueueSchedulerRuntime>().InitializeAsync(),
                diagnostics);
            InitializeService(
                "XDM-STARTUP-BROWSER",
                () => services.GetRequiredService<IBrowserIntegrationService>().InitializeAsync(),
                diagnostics);
        }
        else
        {
            diagnostics.Record(
                DiagnosticSeverity.Warning,
                "XDM-STARTUP-SAFE-MODE",
                "Safe mode skipped queue scheduling and browser integration startup.");
        }

        if (ApplicationLifetime is IClassicDesktopStyleApplicationLifetime desktop)
        {
            desktop.MainWindow = services.GetRequiredService<MainWindow>();
            desktop.Exit += (_, _) =>
            {
                recovery.MarkCleanShutdown();
                diagnostics.Record(
                    DiagnosticSeverity.Information,
                    "XDM-SHUTDOWN-001",
                    "Application shutdown completed cleanly.");
                services.Dispose();
            };
        }

        base.OnFrameworkInitializationCompleted();
    }

#pragma warning disable CA1031 // Startup diagnostics must capture arbitrary service initialization failures.
    private static void InitializeService(
        string code,
        Func<Task> initialize,
        IDiagnosticEventStore diagnostics)
    {
        try
        {
            initialize().GetAwaiter().GetResult();
            diagnostics.Record(DiagnosticSeverity.Information, code, "Service initialized successfully.");
        }
        catch (Exception exception) when (exception is not StackOverflowException and not OutOfMemoryException)
        {
            diagnostics.Record(
                DiagnosticSeverity.Error,
                code,
                $"Service initialization failed: {exception.Message}");
        }
    }

#pragma warning restore CA1031

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

        services.AddSingleton<IDiagnosticEventStore, DiagnosticEventStore>();
        services.AddSingleton<IRecoveryService, RecoveryService>();
        services.AddSingleton<IDiagnosticBundleService, DiagnosticBundleService>();
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
