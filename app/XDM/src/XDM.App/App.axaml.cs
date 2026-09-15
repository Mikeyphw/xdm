using Avalonia;
using Avalonia.Controls;
using Avalonia.Controls.ApplicationLifetimes;
using Avalonia.Markup.Xaml;
using Avalonia.Threading;
using System.ComponentModel;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.Logging;
using XDM.App.Services;
using XDM.App.ViewModels;
using XDM.BrowserIntegration;
using XDM.Core.Abstractions;
using XDM.Core.Persistence;
using XDM.Core.Policies;
using XDM.Core.Scheduling;
using XDM.Core.Settings;
using XDM.Core.State;
using XDM.Core.Diagnostics;
using XDM.Core.Downloads;
using XDM.Diagnostics;
using XDM.DownloadEngine;
using XDM.DownloadEngine.Aria2;
using XDM.DownloadEngine.Policies;
using XDM.DownloadEngine.Queues;
using XDM.Media;
using XDM.Persistence;
using XDM.Platform;

namespace XDM.App;

internal enum ExitIntent
{
    None,
    UserRequested,
    ApplicationRequested,
    RecoveryRestart
}

public partial class App : Application
{
    private ServiceProvider? _services;
    private MainWindowViewModel? _trayViewModel;
    private CrashDiagnosticWiring? _crashDiagnostics;
    private TrayIcon? _mainTrayIcon;
    internal static ExitIntent RequestedExit { get; private set; }
    internal static bool ExitRequested => RequestedExit != ExitIntent.None;

    internal static void RequestExit(ExitIntent intent)
    {
        if (intent != ExitIntent.None)
        {
            RequestedExit = intent;
        }
    }
    internal static StartupOptions LaunchOptions { get; set; } = StartupOptions.Default;

    internal static string? InitialFirefoxHandoffUri { get; set; }

    internal static SingleInstanceCoordinator? InstanceCoordinator { get; set; }

    public override void Initialize()
    {
        AvaloniaXamlLoader.Load(this);
        _mainTrayIcon = ResolveMainTrayIcon();
    }

    public override void OnFrameworkInitializationCompleted()
    {
        ServiceProvider services = ConfigureServices();
        _services = services;
        IRecoveryService recovery = services.GetRequiredService<IRecoveryService>();
        IDiagnosticEventStore diagnostics = services.GetRequiredService<IDiagnosticEventStore>();
        recovery.Initialize(LaunchOptions);
        _crashDiagnostics = CrashDiagnosticWiring.Attach(diagnostics);
        diagnostics.Record(
            DiagnosticSeverity.Information,
            "XDM-STARTUP-001",
            "Application services created.",
            new Dictionary<string, string?>
            {
                ["safeMode"] = recovery.SafeMode ? "true" : "false",
                ["sessionId"] = recovery.SessionId,
                ["uncleanPreviousSession"] = recovery.PreviousSessionWasUnclean ? "true" : "false",
                ["previousActiveDownloads"] = (recovery.PreviousSession?.ActiveDownloadIds.Length ?? 0)
                    .ToString(System.Globalization.CultureInfo.InvariantCulture),
                ["previousCheckpointFlushSucceeded"] = recovery.PreviousSession?.CheckpointFlushSucceeded?.ToString()
            });

        bool coreReady = true;
        coreReady &= InitializeService(
            "XDM-STARTUP-SETTINGS",
            () => services.GetRequiredService<ISettingsService>().InitializeAsync(),
            diagnostics);
        coreReady &= InitializeService(
            "XDM-STARTUP-TRANSFER-POLICY",
            () => services.GetRequiredService<ITransferPolicyRuntime>().InitializeAsync(),
            diagnostics);
        coreReady &= InitializeService(
            "XDM-STARTUP-DOWNLOADS",
            () => services.GetRequiredService<IDownloadManager>().InitializeAsync(),
            diagnostics);
        coreReady &= InitializeService(
            "XDM-STARTUP-RECOVERY-COORDINATOR",
            () => services.GetRequiredService<IDownloadRecoveryCoordinator>()
                .ScanAsync(
                    recovery.PreviousSessionWasUnclean,
                    recovery.PreviousSession?.ActiveDownloadIds,
                    recovery.PreviousSession?.CheckpointFlushSucceeded),
            diagnostics);
        services.GetRequiredService<IApplicationState>().SetCoreReady(coreReady);

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
            InitializeService(
                "XDM-STARTUP-ARIA2",
                () => services.GetRequiredService<IAria2Service>().InitializeAsync(),
                diagnostics);
        }
        else
        {
            diagnostics.Record(
                DiagnosticSeverity.Warning,
                "XDM-STARTUP-SAFE-MODE",
                services.GetRequiredService<LocalizationService>()["ui_safe_mode_diagnostic_skipped"]);
        }

        if (ApplicationLifetime is IClassicDesktopStyleApplicationLifetime desktop)
        {
            desktop.ShutdownMode = Avalonia.Controls.ShutdownMode.OnExplicitShutdown;
            MainWindow mainWindow = services.GetRequiredService<MainWindow>();
            desktop.MainWindow = mainWindow;
            _trayViewModel = services.GetRequiredService<MainWindowViewModel>();
            MainWindowViewModel mainViewModel = _trayViewModel;
            _trayViewModel.PropertyChanged += TrayViewModel_PropertyChanged;
            UpdateTrayStatus();
            RunObservedUpdateHealthAsync(services, diagnostics);
            _ = mainViewModel.InitializeAutomaticUpdateCheckAsync();
            if (InstanceCoordinator is not null)
            {
                InstanceCoordinator.ActivationRequested += (_, activation) =>
                    Dispatcher.UIThread.Post(() =>
                    {
                        mainWindow.RestoreAndActivate();
                        if (!string.IsNullOrWhiteSpace(activation.Payload))
                        {
                            _ = mainViewModel.HandleFirefoxExtensionHandoffAsync(activation.Payload);
                        }
                    });
                if (!InstanceCoordinator.StartListening())
                {
                    diagnostics.Record(
                        DiagnosticSeverity.Warning,
                        "XDM-STARTUP-SINGLE-INSTANCE",
                        "The activation listener could not be started; duplicate launches will still be blocked.");
                }
            }

            if (!string.IsNullOrWhiteSpace(InitialFirefoxHandoffUri))
            {
                string initialHandoff = InitialFirefoxHandoffUri;
                InitialFirefoxHandoffUri = null;
                Dispatcher.UIThread.Post(() => _ = mainViewModel.HandleFirefoxExtensionHandoffAsync(initialHandoff));
            }

            desktop.Exit += (_, _) =>
                HandleDesktopExit(services, recovery, diagnostics);
        }

        base.OnFrameworkInitializationCompleted();
    }


    private static void RunObservedUpdateHealthAsync(
        ServiceProvider services,
        IDiagnosticEventStore diagnostics)
    {
        _ = Task.Run(async () =>
        {
            try
            {
                await services.GetRequiredService<IUpdateService>()
                    .MarkCurrentVersionHealthyAsync()
                    .ConfigureAwait(false);
                diagnostics.Record(
                    DiagnosticSeverity.Information,
                    "XDM-STARTUP-UPDATE-HEALTH",
                    "Observed update health task completed.");
            }
            catch (Exception exception) when (exception is not StackOverflowException and not OutOfMemoryException)
            {
                diagnostics.Record(
                    DiagnosticSeverity.Error,
                    "XDM-STARTUP-UPDATE-HEALTH",
                    $"Observed update health task failed: {exception.Message}");
            }
        });
    }

#pragma warning disable CA1031 // Startup diagnostics must capture arbitrary service initialization failures.
    private static bool InitializeService(
        string code,
        Func<Task> initialize,
        IDiagnosticEventStore diagnostics)
    {
        try
        {
            initialize().GetAwaiter().GetResult();
            diagnostics.Record(DiagnosticSeverity.Information, code, "Service initialized successfully.");
            return true;
        }
        catch (Exception exception) when (exception is not StackOverflowException and not OutOfMemoryException)
        {
            diagnostics.Record(
                DiagnosticSeverity.Error,
                code,
                $"Service initialization failed: {exception.Message}");
            return false;
        }
    }

#pragma warning restore CA1031



    private void HandleDesktopExit(
        ServiceProvider services,
        IRecoveryService recovery,
        IDiagnosticEventStore diagnostics)
    {
        try
        {
            ShutdownCoordinator.ExecuteAsync(services, recovery, diagnostics)
                .GetAwaiter()
                .GetResult();
        }
        catch (Exception exception) when (exception is IOException
            or UnauthorizedAccessException
            or InvalidOperationException
            or TimeoutException)
        {
            diagnostics.Record(
                DiagnosticSeverity.Error,
                "XDM-SHUTDOWN-FAILED",
                $"Shutdown did not complete cleanly: {exception.Message}");
        }
        finally
        {
            if (_trayViewModel is not null)
            {
                _trayViewModel.PropertyChanged -= TrayViewModel_PropertyChanged;
            }
            _crashDiagnostics?.Dispose();
            _crashDiagnostics = null;
            _services = null;
        }
    }

    private void TrayOpen_Click(object? sender, EventArgs eventArgs)
    {
        if (ApplicationLifetime is IClassicDesktopStyleApplicationLifetime { MainWindow: MainWindow window })
        {
            window.RestoreAndActivate();
        }
    }

    internal void ShowMiniWindow()
        => _services?.GetRequiredService<MiniWindow>().RestoreAndActivate();

    private void TrayMini_Click(object? sender, EventArgs eventArgs)
        => ShowMiniWindow();

    private async void TrayBandwidthUnlimited_Click(object? sender, EventArgs eventArgs)
        => await ApplyTrayBandwidthAsync(0);

    private async void TrayBandwidth1MiB_Click(object? sender, EventArgs eventArgs)
        => await ApplyTrayBandwidthAsync(1024 * 1024);

    private async void TrayBandwidth5MiB_Click(object? sender, EventArgs eventArgs)
        => await ApplyTrayBandwidthAsync(5 * 1024 * 1024);

    private async void TrayBandwidth10MiB_Click(object? sender, EventArgs eventArgs)
        => await ApplyTrayBandwidthAsync(10 * 1024 * 1024);

    private async Task ApplyTrayBandwidthAsync(long bytesPerSecond)
    {
        if (_trayViewModel is null)
        {
            return;
        }

        try
        {
            await _trayViewModel.ApplyQuickBandwidthLimitAsync(bytesPerSecond);
            UpdateTrayStatus();
        }
        catch (IOException exception)
        {
            _trayViewModel.OperationMessage = $"Could not update the bandwidth limit: {exception.Message}";
        }
        catch (UnauthorizedAccessException exception)
        {
            _trayViewModel.OperationMessage = $"Could not update the bandwidth limit: {exception.Message}";
        }
        catch (InvalidOperationException exception)
        {
            _trayViewModel.OperationMessage = $"Could not update the bandwidth limit: {exception.Message}";
        }
    }

    private void TrayViewModel_PropertyChanged(object? sender, PropertyChangedEventArgs eventArgs)
    {
        if (eventArgs.PropertyName is nameof(MainWindowViewModel.ActiveDownloadCount)
            or nameof(MainWindowViewModel.AggregateSpeed)
            or nameof(MainWindowViewModel.AggregateProgressText))
        {
            Dispatcher.UIThread.Post(UpdateTrayStatus);
        }
    }

    private TrayIcon? ResolveMainTrayIcon()
    {
        TrayIcons? trayIcons = TrayIcon.GetIcons(this);
        return trayIcons is { Count: > 0 } ? trayIcons[0] : null;
    }

    private void UpdateTrayStatus()
    {
        TrayIcon? trayIcon = _mainTrayIcon ??= ResolveMainTrayIcon();
        if (trayIcon is null)
        {
            return;
        }

        trayIcon.ToolTipText = _trayViewModel is null
            ? "Xtreme Download Manager"
            : $"XDM • {_trayViewModel.ActiveDownloadCount} active • {_trayViewModel.AggregateProgressText} • {_trayViewModel.AggregateSpeed}";
    }

    private void TrayExit_Click(object? sender, EventArgs eventArgs)
    {
        RequestExit(ExitIntent.UserRequested);
        if (ApplicationLifetime is IClassicDesktopStyleApplicationLifetime desktop)
        {
            desktop.Shutdown();
        }
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

        services.AddSingleton(static provider =>
            ConfiguredHttpClientFactory.Create(provider.GetRequiredService<ISettingsService>()));

        services.AddSingleton<IDiagnosticEventStore>(static _ => DiagnosticEventStore.Persistent());
        services.AddSingleton(static _ => TransferDiagnosticStore.Persistent());
        services.AddSingleton<ITransferDiagnosticSink>(static provider => provider.GetRequiredService<TransferDiagnosticStore>());
        services.AddSingleton<ITransferDiagnosticSource>(static provider => provider.GetRequiredService<TransferDiagnosticStore>());
        services.AddSingleton<ITransferHealthProbe, TransferHealthProbe>();
        services.AddSingleton<ISubsystemHealthService, SubsystemHealthService>();
        services.AddSingleton<IDeterministicDownloadTestService, DeterministicDownloadTestService>();
        services.AddSingleton<IRecoveryService, RecoveryService>();
        services.AddSingleton<IDiagnosticBundleService, DiagnosticBundleService>();
        services.AddSingleton<IBrowserIntegrationService, LoopbackBrowserIntegrationService>();
        services.AddSingleton<IExternalToolRunner, ExternalToolRunner>();
        services.AddSingleton<IYtDlpNetworkPolicyProvider, SettingsYtDlpNetworkPolicyProvider>();
        services.AddSingleton<IYtDlpProvider, YtDlpProvider>();
        services.AddSingleton<IFfmpegService, FfmpegService>();
        services.AddSingleton<IConversionService, ConversionService>();
        services.AddSingleton<IConversionQueueService, ConversionQueueService>();
        services.AddSingleton<IMediaCatalogService, MediaCatalogService>();
        services.AddSingleton<IMediaDownloadService, MediaDownloadService>();
        services.AddSingleton<IMediaProbeService, MediaProbeService>();
        services.AddSingleton<IApplicationState, ApplicationState>();
        services.AddSingleton<IDownloadHistoryStore, JsonDownloadHistoryStore>();
        services.AddSingleton<IDownloadListTransferService, DownloadListTransferService>();
        services.AddSingleton<ISettingsStore, JsonSettingsStore>();
        services.AddSingleton<ISettingsService, SettingsService>();
        services.AddSingleton<IAria2Service, Aria2Service>();
        services.AddSingleton<LocalizationService>();
        services.AddSingleton<ISettingsTransferService, SettingsTransferService>();
        services.AddSingleton<ISchedulerStateStore, JsonSchedulerStateStore>();
        services.AddSingleton<IApplicationLifetimeService, AvaloniaApplicationLifetimeService>();
        services.AddSingleton<IPlatformCommandRunner, PlatformCommandRunner>();
        services.AddSingleton<ICompletionActionService, PlatformCompletionActionService>();
        services.AddSingleton<IAntivirusScanner, AntivirusScanner>();
        services.AddSingleton<ITransferEnvironmentProbe, SystemTransferEnvironmentProbe>();
        services.AddSingleton<ITransferPolicyRuntime, TransferPolicyRuntime>();
        services.AddSingleton<DownloadChecksumWorkflowStore>();
        services.AddSingleton<FinalizationJournalStore>();
        services.AddSingleton<IFinalizationFilePromoter, FinalizationFilePromoter>();
        services.AddSingleton<IPartialFileRepairService>(static provider =>
            new PartialFileRepairService(provider.GetRequiredService<HttpClient>()));
        services.AddSingleton<IDownloadManager, DownloadManager>();
        services.AddSingleton<IDownloadRecoveryCoordinator, DownloadRecoveryCoordinator>();
        services.AddSingleton<IQueueSchedulerRuntime, QueueSchedulerRuntime>();
        services.AddSingleton<IPlatformInfo, PlatformInfo>();
        services.AddSingleton<IPlatformService, DesktopPlatformService>();
        services.AddSingleton<IUpdateService, VerifiedUpdateService>();
        services.AddSingleton<DesktopNotificationService>();
        services.AddSingleton<NotificationCenterService>();
        services.AddSingleton<INotificationCenterService>(static provider => provider.GetRequiredService<NotificationCenterService>());
        services.AddSingleton<IDesktopNotificationService>(static provider => provider.GetRequiredService<NotificationCenterService>());
        services.AddSingleton<DesktopProductivityStateStore>();
        services.AddSingleton<IBrowserHostInstaller, BrowserHostInstaller>();
        services.AddSingleton<IUiDispatcher, AvaloniaUiDispatcher>();
        services.AddSingleton<WindowStateStore>();
        services.AddSingleton<MainWindowViewModel>();
        services.AddSingleton<MainWindow>();
        services.AddSingleton<MiniWindow>();

        return services.BuildServiceProvider(new ServiceProviderOptions
        {
            ValidateOnBuild = true,
            ValidateScopes = true
        });
    }
}
