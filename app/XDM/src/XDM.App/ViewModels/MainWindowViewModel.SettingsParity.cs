using System.Collections.ObjectModel;
using System.Text.Json;
using CommunityToolkit.Mvvm.ComponentModel;
using CommunityToolkit.Mvvm.Input;
using XDM.Core.Settings;

namespace XDM.App.ViewModels;

public partial class MainWindowViewModel
{
    private readonly ISettingsTransferService _settingsTransferService;

    public IReadOnlyList<ProxyMode> ProxyModes { get; } = Enum.GetValues<ProxyMode>();

    public ObservableCollection<ServerCredentialDefinition> ServerCredentials { get; } = [];

    [ObservableProperty]
    private string connectTimeoutSeconds = "30";

    [ObservableProperty]
    private string requestTimeoutSeconds = "0";

    [ObservableProperty]
    private string maximumRetryAttempts = "3";

    [ObservableProperty]
    private string retryBaseDelayMilliseconds = "350";

    [ObservableProperty]
    private string defaultConnectionCount = "4";

    [ObservableProperty]
    private string maximumConnectionCount = "16";

    [ObservableProperty]
    private string minimumSegmentedSizeMegabytes = "1";

    [ObservableProperty]
    private ProxyMode selectedProxyMode = ProxyMode.System;

    [ObservableProperty]
    private string proxyHost = string.Empty;

    [ObservableProperty]
    private string proxyPort = "8080";

    [ObservableProperty]
    private string proxyUsername = string.Empty;

    [ObservableProperty]
    private string proxyPassword = string.Empty;

    [ObservableProperty]
    private bool proxyBypassLocal = true;

    [ObservableProperty]
    private string proxyBypassList = string.Empty;

    [ObservableProperty]
    private bool createDestinationDirectory = true;

    [ObservableProperty]
    private bool autoSelectCategory = true;

    [ObservableProperty]
    private bool rememberLastRequestMetadata;

    [ObservableProperty]
    private ServerCredentialDefinition? selectedServerCredential;

    [ObservableProperty]
    private string credentialHost = string.Empty;

    [ObservableProperty]
    private string credentialUsername = string.Empty;

    [ObservableProperty]
    private string credentialPassword = string.Empty;

    [ObservableProperty]
    private bool credentialIncludesSubdomains;

    [ObservableProperty]
    private string settingsTransferPath = string.Empty;

    [ObservableProperty]
    private bool includeSecretsInSettingsExport;

    [ObservableProperty]
    private string settingsTransferStatus = "Choose a modern export or a legacy XDM settings file/directory.";

    [RelayCommand]
    private void AddServerCredential()
    {
        ServerCredentialDefinition credential = new(
            CredentialHost,
            CredentialUsername,
            CredentialPassword,
            CredentialIncludesSubdomains);
        credential = credential.Normalize();
        if (credential.Host.Length == 0 || credential.Username.Length == 0)
        {
            OperationMessage = "Credential host and username are required.";
            return;
        }

        ServerCredentialDefinition? existing = ServerCredentials.FirstOrDefault(item =>
            string.Equals(item.Host, credential.Host, StringComparison.OrdinalIgnoreCase));
        if (existing is not null)
        {
            ServerCredentials.Remove(existing);
        }
        ServerCredentials.Add(credential);
        SelectedServerCredential = credential;
        CredentialHost = string.Empty;
        CredentialUsername = string.Empty;
        CredentialPassword = string.Empty;
        CredentialIncludesSubdomains = false;
        OperationMessage = "Credential updated; save settings to persist it.";
    }

    [RelayCommand]
    private void RemoveSelectedServerCredential()
    {
        if (SelectedServerCredential is null)
        {
            return;
        }
        ServerCredentials.Remove(SelectedServerCredential);
        SelectedServerCredential = ServerCredentials.Count > 0 ? ServerCredentials[0] : null;
        OperationMessage = "Credential removed; save settings to persist it.";
    }

    [RelayCommand]
    private async Task ExportSettingsAsync()
    {
        if (string.IsNullOrWhiteSpace(SettingsTransferPath))
        {
            SettingsTransferStatus = "Choose an export file first.";
            return;
        }
        try
        {
            await _settingsTransferService.ExportAsync(
                SettingsTransferPath,
                BuildSettingsFromEditor(),
                IncludeSecretsInSettingsExport);
            SettingsTransferStatus = IncludeSecretsInSettingsExport
                ? "Settings exported with credentials. Protect the file as sensitive data."
                : "Settings exported with passwords redacted.";
        }
        catch (IOException exception)
        {
            SettingsTransferStatus = exception.Message;
        }
        catch (UnauthorizedAccessException exception)
        {
            SettingsTransferStatus = exception.Message;
        }
    }

    [RelayCommand]
    private async Task ImportSettingsAsync()
    {
        if (string.IsNullOrWhiteSpace(SettingsTransferPath))
        {
            SettingsTransferStatus = "Choose a modern or legacy settings source first.";
            return;
        }
        try
        {
            SettingsImportResult result = await _settingsTransferService.ImportAsync(
                SettingsTransferPath,
                _settingsService.Current);
            await _settingsService.UpdateAsync(result.Settings);
            string warnings = result.Warnings.Count == 0
                ? string.Empty
                : $" {string.Join(" ", result.Warnings)}";
            SettingsTransferStatus = $"Imported {result.SourceFormat}: {result.ImportedCategoryCount} categories, {result.ImportedQueueCount} queues, {result.ImportedCredentialCount} credentials.{warnings}";
        }
        catch (JsonException exception)
        {
            SettingsTransferStatus = $"Invalid JSON: {exception.Message}";
        }
        catch (InvalidDataException exception)
        {
            SettingsTransferStatus = exception.Message;
        }
        catch (IOException exception)
        {
            SettingsTransferStatus = exception.Message;
        }
        catch (UnauthorizedAccessException exception)
        {
            SettingsTransferStatus = exception.Message;
        }
    }

    private ApplicationSettings BuildSettingsFromEditor()
    {
        ApplicationSettings current = _settingsService.Current;
        NetworkSettings existingNetwork = current.Network ?? NetworkSettings.Default;
        ProxySettings existingProxy = existingNetwork.Proxy ?? ProxySettings.SystemDefault;
        int connectTimeout = ParseInteger(ConnectTimeoutSeconds, existingNetwork.ConnectTimeoutSeconds);
        int requestTimeout = ParseInteger(RequestTimeoutSeconds, existingNetwork.RequestTimeoutSeconds);
        int retries = ParseInteger(MaximumRetryAttempts, existingNetwork.MaximumRetryAttempts);
        int retryDelay = ParseInteger(RetryBaseDelayMilliseconds, existingNetwork.RetryBaseDelayMilliseconds);
        int defaultConnections = ParseInteger(DefaultConnectionCount, existingNetwork.DefaultConnectionCount);
        int maximumConnections = ParseInteger(MaximumConnectionCount, existingNetwork.MaximumConnectionCount);
        double minimumMegabytes = ParseDouble(MinimumSegmentedSizeMegabytes, 1);
        int proxyPortValue = ParseInteger(ProxyPort, existingProxy.Port);

        return current with
        {
            Network = new NetworkSettings(
                connectTimeout,
                requestTimeout,
                retries,
                retryDelay,
                defaultConnections,
                maximumConnections,
                checked((long)(Math.Max(0.0625, minimumMegabytes) * 1024 * 1024)),
                new ProxySettings(
                    SelectedProxyMode,
                    EmptyToNull(ProxyHost),
                    proxyPortValue,
                    EmptyToNull(ProxyUsername),
                    EmptyToNull(ProxyPassword),
                    ProxyBypassLocal,
                    ProxyBypassList.Split(LineSeparators, StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries))),
            DownloadBehavior = new DownloadBehaviorSettings(
                SelectedDuplicateBehavior,
                CreateDestinationDirectory,
                AutoSelectCategory,
                RememberLastRequestMetadata),
            Credentials = ServerCredentials.ToArray()
        };
    }

    private void ApplySettingsParity(ApplicationSettings settings)
    {
        NetworkSettings network = settings.Network ?? NetworkSettings.Default;
        ProxySettings proxy = network.Proxy ?? ProxySettings.SystemDefault;
        DownloadBehaviorSettings behavior = settings.DownloadBehavior ?? DownloadBehaviorSettings.Default;
        ConnectTimeoutSeconds = network.ConnectTimeoutSeconds.ToString(System.Globalization.CultureInfo.InvariantCulture);
        RequestTimeoutSeconds = network.RequestTimeoutSeconds.ToString(System.Globalization.CultureInfo.InvariantCulture);
        MaximumRetryAttempts = network.MaximumRetryAttempts.ToString(System.Globalization.CultureInfo.InvariantCulture);
        RetryBaseDelayMilliseconds = network.RetryBaseDelayMilliseconds.ToString(System.Globalization.CultureInfo.InvariantCulture);
        DefaultConnectionCount = network.DefaultConnectionCount.ToString(System.Globalization.CultureInfo.InvariantCulture);
        MaximumConnectionCount = network.MaximumConnectionCount.ToString(System.Globalization.CultureInfo.InvariantCulture);
        MinimumSegmentedSizeMegabytes = (network.MinimumSegmentedSizeBytes / 1024d / 1024d)
            .ToString("0.###", System.Globalization.CultureInfo.InvariantCulture);
        SelectedProxyMode = proxy.Mode;
        ProxyHost = proxy.Host ?? string.Empty;
        ProxyPort = proxy.Port.ToString(System.Globalization.CultureInfo.InvariantCulture);
        ProxyUsername = proxy.Username ?? string.Empty;
        ProxyPassword = proxy.Password ?? string.Empty;
        ProxyBypassLocal = proxy.BypassLocal;
        ProxyBypassList = string.Join(Environment.NewLine, proxy.BypassList ?? []);
        SelectedDuplicateBehavior = behavior.DefaultDuplicateBehavior;
        CreateDestinationDirectory = behavior.CreateDestinationDirectory;
        AutoSelectCategory = behavior.AutoSelectCategory;
        RememberLastRequestMetadata = behavior.RememberLastRequestMetadata;
        ServerCredentials.Clear();
        foreach (ServerCredentialDefinition credential in settings.Credentials ?? [])
        {
            ServerCredentials.Add(credential);
        }
        SelectedServerCredential = ServerCredentials.Count > 0 ? ServerCredentials[0] : null;
    }

    private string? ResolveCategoryId(Uri source, string? selectedCategoryId)
    {
        if (!AutoSelectCategory)
        {
            return selectedCategoryId;
        }
        string extension = Path.GetExtension(source.AbsolutePath).TrimStart('.');
        if (extension.Length == 0)
        {
            return selectedCategoryId;
        }
        DownloadCategoryDefinition? category = CategoryDefinitions.FirstOrDefault(item =>
            item.Extensions.Any(value => string.Equals(value, extension, StringComparison.OrdinalIgnoreCase)));
        return category?.Id ?? selectedCategoryId;
    }

    private (string? Username, string? Password) ResolveServerCredential(Uri source)
    {
        ServerCredentialDefinition? credential = ServerCredentials.FirstOrDefault(item => item.Matches(source));
        return credential is null ? (null, null) : (credential.Username, credential.Password);
    }

    private static int ParseInteger(string value, int fallback)
        => int.TryParse(value, System.Globalization.NumberStyles.Integer, System.Globalization.CultureInfo.InvariantCulture, out int result)
            ? result
            : fallback;

    private static double ParseDouble(string value, double fallback)
        => double.TryParse(value, System.Globalization.NumberStyles.Float, System.Globalization.CultureInfo.InvariantCulture, out double result)
            ? result
            : fallback;
}
