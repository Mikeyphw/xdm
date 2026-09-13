using System.Collections.ObjectModel;
using CommunityToolkit.Mvvm.ComponentModel;
using CommunityToolkit.Mvvm.Input;
using XDM.Core.Downloads;
using XDM.Core.Settings;
using XDM.DownloadEngine;

namespace XDM.App.ViewModels;

public partial class MainWindowViewModel
{
    private static readonly char[] DestinationRuleExtensionSeparators = [',', ';', ' '];
    private CancellationTokenSource? _destinationPreviewCancellation;
    private long _destinationPreviewVersion;
    public ObservableCollection<SavedSearchDefinition> SavedSearches { get; } = [];

    public ObservableCollection<DestinationRuleDefinition> DestinationRules { get; } = [];

    public IReadOnlyList<DuplicateUrlBehavior> DuplicateUrlBehaviors { get; } = Enum.GetValues<DuplicateUrlBehavior>();

    [ObservableProperty]
    private SavedSearchDefinition? selectedSavedSearch;

    [ObservableProperty]
    private string newSavedSearchName = string.Empty;

    [ObservableProperty]
    private string newDownloadTags = string.Empty;

    [ObservableProperty]
    private string destinationConflictPreview = string.Empty;

    [ObservableProperty]
    private bool hasDestinationConflict;

    [ObservableProperty]
    private string selectedDownloadTags = string.Empty;

    [ObservableProperty]
    private string relinkDestinationPath = string.Empty;

    [ObservableProperty]
    private DuplicateUrlBehavior selectedDuplicateUrlBehavior = DuplicateUrlBehavior.FocusExisting;

    [ObservableProperty]
    private bool computeContentHashes;

    [ObservableProperty]
    private DestinationRuleDefinition? selectedDestinationRule;

    [ObservableProperty]
    private string newDestinationRuleName = string.Empty;

    [ObservableProperty]
    private string newDestinationRuleHost = string.Empty;

    [ObservableProperty]
    private string newDestinationRuleExtensions = string.Empty;

    [ObservableProperty]
    private string newDestinationRuleDirectory = string.Empty;

    [ObservableProperty]
    private string newDestinationRuleTags = string.Empty;

    partial void OnNewDownloadUrlsChanged(string value)
        => _ = RefreshDestinationConflictPreviewAsync();

    partial void OnDestinationFolderChanged(string value)
        => _ = RefreshDestinationConflictPreviewAsync();

    partial void OnCustomFileNameChanged(string value)
        => _ = RefreshDestinationConflictPreviewAsync();

    partial void OnSelectedDuplicateBehaviorChanged(string value)
    {
        if (!_localizedChoiceSelectionSyncing)
        {
            SyncDuplicateBehaviorChoiceFromValue();
        }

        _ = RefreshDestinationConflictPreviewAsync();
    }

    private async Task RefreshDestinationConflictPreviewAsync()
    {
        _destinationPreviewCancellation?.Cancel();
        _destinationPreviewCancellation?.Dispose();
        CancellationTokenSource cancellation = new();
        _destinationPreviewCancellation = cancellation;
        long version = ++_destinationPreviewVersion;

        DownloadUrlParseResult parsed = DownloadInputParser.ParseUrlsDetailed(NewDownloadUrls);
        if (parsed.AcceptedUrls.Count == 0 || string.IsNullOrWhiteSpace(DestinationFolder))
        {
            DestinationConflictPreview = string.Empty;
            HasDestinationConflict = false;
            return;
        }

        DuplicateFileBehavior duplicateBehavior = Enum.TryParse(
            SelectedDuplicateBehavior,
            ignoreCase: true,
            out DuplicateFileBehavior parsedBehavior)
                ? parsedBehavior
                : DuplicateFileBehavior.AutoRename;
        ApplicationSettings settings = _settingsService.Current;
        IReadOnlyDictionary<string, string> headers = DownloadInputParser.ParseHeaders(RequestHeaders);
        long? speedLimit = ParseKilobytesPerSecond(SpeedLimitKbps);
        IReadOnlyList<Uri> sources = parsed.AcceptedUrls;
        List<DownloadRequest> requests = new(sources.Count);
        foreach (Uri source in sources)
        {
            DownloadCategoryRoute route = DownloadCategoryRouting.Resolve(
                settings,
                source,
                SelectedCategory?.Id,
                DestinationFolder);
            (string? savedUsername, string? savedPassword) = ResolveServerCredential(source);
            requests.Add(new DownloadRequest(
                source,
                route.DestinationDirectory,
                sources.Count == 1 && !string.IsNullOrWhiteSpace(CustomFileName) ? CustomFileName.Trim() : null,
                headers,
                EmptyToNull(Username) ?? savedUsername,
                EmptyToNull(Password) ?? savedPassword,
                EmptyToNull(Cookie),
                EmptyToNull(Referer),
                EmptyToNull(UserAgent),
                SelectedQueue?.Id,
                route.CategoryId,
                speedLimit,
                duplicateBehavior,
                ConnectionCount: (settings.Network ?? NetworkSettings.Default).Normalize().DefaultConnectionCount,
                Priority: NewDownloadPriority,
                SourcePage: ParseOptionalHttpUri(Referer),
                Mirrors: DownloadInputParser.ParseUrls(MirrorUrls),
                ExpectedChecksumAlgorithm: EmptyToNull(ExpectedChecksumAlgorithm),
                ExpectedChecksum: EmptyToNull(ExpectedChecksum),
                BackendPreference: NewDownloadBackendPreference,
                AllowBackendFallback: NewDownloadAllowBackendFallback,
                Tags: DownloadMetadata.ParseTags(NewDownloadTags),
                ExpectedSha256: EmptyToNull(ExpectedSha256),
                ExpectedSha512: EmptyToNull(ExpectedSha512)));
        }

        try
        {
            IReadOnlyList<DownloadAdmissionPreview> previews = await Task.Run(
                () => _downloadManager.PreviewBatchAdmissionAsync(requests, cancellation.Token),
                cancellation.Token);
            if (cancellation.IsCancellationRequested || version != _destinationPreviewVersion || previews.Count == 0)
            {
                return;
            }

            DownloadAdmissionPreview first = previews[0];
            HasDestinationConflict = previews.Any(static preview => preview.HasConflict);
            string batch = previews.Count > 1 ? $" Batch plans {previews.Count} destinations; showing first." : string.Empty;
            DestinationConflictPreview = first.HasConflict
                ? $"Destination conflict: {first.DestinationPath}. {SelectedDuplicateBehavior} will be applied.{batch}"
                : $"Destination available: {first.DestinationPath}.{batch}";
        }
        catch (OperationCanceledException) when (cancellation.IsCancellationRequested)
        {
        }
        catch (Exception exception) when (exception is ArgumentException
            or IOException
            or UnauthorizedAccessException
            or InvalidOperationException)
        {
            if (version == _destinationPreviewVersion)
            {
                HasDestinationConflict = true;
                DestinationConflictPreview = $"Destination preview unavailable: {exception.Message}";
            }
        }
    }

    [RelayCommand]
    private async Task RefreshOrganizationStateAsync()
    {
        DownloadItemViewModel[] completed = Downloads
            .Where(static download => download.State == DownloadState.Completed)
            .ToArray();
        (DownloadItemViewModel Item, bool Missing)[] results = await Task.Run(() => completed
            .Select(item => (item, !File.Exists(item.DestinationPath)))
            .ToArray());
        bool changed = false;
        foreach ((DownloadItemViewModel item, bool missing) in results)
        {
            if (item.IsFileMissing != missing)
            {
                changed = true;
                item.SetFileMissing(missing);
            }
        }
        if (changed)
        {
            RefreshFilteredDownloads();
        }
        OperationMessage = "Download organization state refreshed.";
    }

    [RelayCommand]
    private void ApplySavedSearch()
    {
        if (SelectedSavedSearch is not null)
        {
            DownloadSearchText = SelectedSavedSearch.Query;
        }
    }

    [RelayCommand]
    private void SaveCurrentSearch()
    {
        string name = NewSavedSearchName.Trim();
        string query = DownloadSearchText.Trim();
        if (name.Length == 0 || query.Length == 0)
        {
            OperationMessage = "Enter a smart-collection name and a search query.";
            return;
        }

        string id = CreateStableId(name, SavedSearches.Select(static search => search.Id));
        SavedSearchDefinition search = new(id, name, query);
        SavedSearches.Add(search);
        SelectedSavedSearch = search;
        NewSavedSearchName = string.Empty;
        OperationMessage = "Smart collection added; save settings to persist it.";
    }

    [RelayCommand]
    private void RemoveSelectedSavedSearch()
    {
        if (SelectedSavedSearch is null)
        {
            return;
        }

        SavedSearches.Remove(SelectedSavedSearch);
        SelectedSavedSearch = SavedSearches.FirstOrDefault();
        OperationMessage = "Smart collection removed; save settings to persist the change.";
    }

    [RelayCommand]
    private async Task ApplySelectedDownloadTagsAsync()
    {
        if (SelectedDownload is null)
        {
            return;
        }

        await _downloadManager.SetTagsAsync(
            SelectedDownload.Id,
            DownloadMetadata.ParseTags(SelectedDownloadTags));
        OperationMessage = "Download tags updated.";
    }

    [RelayCommand]
    private async Task ToggleSelectedDownloadArchiveAsync()
    {
        if (SelectedDownload is null)
        {
            return;
        }

        bool archived = !SelectedDownload.IsArchived;
        try
        {
            await _downloadManager.SetArchivedAsync(SelectedDownload.Id, archived);
            OperationMessage = archived ? "Download archived." : "Download restored from archive.";
        }
        catch (InvalidOperationException exception)
        {
            OperationMessage = exception.Message;
        }
    }

    [RelayCommand]
    private async Task RelinkSelectedDownloadAsync()
    {
        if (SelectedDownload is null || string.IsNullOrWhiteSpace(RelinkDestinationPath))
        {
            OperationMessage = "Choose an existing file to relink.";
            return;
        }

        try
        {
            await _downloadManager.RelinkAsync(SelectedDownload.Id, RelinkDestinationPath);
            OperationMessage = "Download relinked to the existing file.";
        }
        catch (IOException exception)
        {
            OperationMessage = exception.Message;
        }
        catch (UnauthorizedAccessException exception)
        {
            OperationMessage = exception.Message;
        }
        catch (InvalidOperationException exception)
        {
            OperationMessage = exception.Message;
        }
    }

    [RelayCommand]
    private void AddDestinationRule()
    {
        string name = NewDestinationRuleName.Trim();
        string directory = NewDestinationRuleDirectory.Trim();
        if (name.Length == 0 || directory.Length == 0)
        {
            OperationMessage = "Enter a destination-rule name and directory.";
            return;
        }

        string id = CreateStableId(name, DestinationRules.Select(static rule => rule.Id));
        DestinationRuleDefinition rule = new(
            id,
            name,
            true,
            DestinationRules.Count,
            directory,
            string.IsNullOrWhiteSpace(NewDestinationRuleHost) ? null : NewDestinationRuleHost.Trim(),
            null,
            NewDestinationRuleExtensions.Split(DestinationRuleExtensionSeparators, StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries),
            null,
            DownloadMetadata.ParseTags(NewDestinationRuleTags));
        DestinationRules.Add(rule.Normalize());
        SelectedDestinationRule = DestinationRules[^1];
        NewDestinationRuleName = string.Empty;
        NewDestinationRuleHost = string.Empty;
        NewDestinationRuleExtensions = string.Empty;
        NewDestinationRuleDirectory = string.Empty;
        NewDestinationRuleTags = string.Empty;
        _ = RefreshDestinationConflictPreviewAsync();
        OperationMessage = "Destination rule added; save settings to activate it.";
    }

    [RelayCommand]
    private void RemoveSelectedDestinationRule()
    {
        if (SelectedDestinationRule is null)
        {
            return;
        }

        DestinationRules.Remove(SelectedDestinationRule);
        SelectedDestinationRule = DestinationRules.FirstOrDefault();
        _ = RefreshDestinationConflictPreviewAsync();
        OperationMessage = "Destination rule removed; save settings to persist the change.";
    }

    private void ApplyOrganizationSettings(ApplicationSettings settings)
    {
        OrganizationSettings organization = (settings.Organization ?? OrganizationSettings.Default).Normalize();
        SelectedDuplicateUrlBehavior = organization.DuplicateUrlBehavior;
        ComputeContentHashes = organization.ComputeContentHashes;
        SavedSearches.Clear();
        foreach (SavedSearchDefinition search in organization.SavedSearches)
        {
            SavedSearches.Add(search);
        }
        SelectedSavedSearch = SavedSearches.FirstOrDefault();

        DestinationRules.Clear();
        foreach (DestinationRuleDefinition rule in organization.DestinationRules)
        {
            DestinationRules.Add(rule);
        }
        SelectedDestinationRule = DestinationRules.FirstOrDefault();
        _ = RefreshDestinationConflictPreviewAsync();
    }

    private OrganizationSettings BuildOrganizationSettings()
        => new(
            SelectedDuplicateUrlBehavior,
            ComputeContentHashes,
            DestinationRules.ToArray(),
            SavedSearches.ToArray());
}
