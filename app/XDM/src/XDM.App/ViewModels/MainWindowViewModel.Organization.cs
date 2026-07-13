using System.Collections.ObjectModel;
using CommunityToolkit.Mvvm.ComponentModel;
using CommunityToolkit.Mvvm.Input;
using XDM.Core.Downloads;
using XDM.Core.Settings;

namespace XDM.App.ViewModels;

public partial class MainWindowViewModel
{
    private static readonly char[] DestinationRuleExtensionSeparators = [',', ';', ' '];
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
        => RefreshDestinationConflictPreview();

    partial void OnDestinationFolderChanged(string value)
        => RefreshDestinationConflictPreview();

    partial void OnCustomFileNameChanged(string value)
        => RefreshDestinationConflictPreview();

    partial void OnSelectedDuplicateBehaviorChanged(string value)
        => RefreshDestinationConflictPreview();

    private void RefreshDestinationConflictPreview()
    {
        IReadOnlyList<Uri> sources = DownloadInputParser.ParseUrls(NewDownloadUrls);
        Uri? source = sources.Count > 0 ? sources[0] : null;
        if (source is null || string.IsNullOrWhiteSpace(DestinationFolder))
        {
            DestinationConflictPreview = string.Empty;
            HasDestinationConflict = false;
            return;
        }

        string fileName = string.IsNullOrWhiteSpace(CustomFileName)
            ? Uri.UnescapeDataString(Path.GetFileName(source.LocalPath))
            : CustomFileName.Trim();
        if (string.IsNullOrWhiteSpace(fileName))
        {
            fileName = "download.bin";
        }

        OrganizationSettings activeOrganization = (_settingsService.Current.Organization ?? OrganizationSettings.Default).Normalize();
        DestinationRuleDefinition? rule = activeOrganization.DestinationRules
            .FirstOrDefault(candidate => candidate.Matches(source, fileName));
        string directory = rule?.DestinationDirectory ?? DestinationFolder;
        string path = Path.Combine(directory, fileName);
        bool collision = File.Exists(path)
            || Downloads.Any(download => string.Equals(download.DestinationPath, path, StringComparison.OrdinalIgnoreCase));
        HasDestinationConflict = collision;
        string routing = rule is null ? string.Empty : $" Rule '{rule.Name}' routes this download to {directory}.";
        DestinationConflictPreview = collision
            ? $"Destination conflict: {path}. {SelectedDuplicateBehavior} will be applied.{routing}"
            : $"Destination available: {path}.{routing}";
    }

    [RelayCommand]
    private void RefreshOrganizationState()
    {
        foreach (DownloadItemViewModel download in Downloads)
        {
            download.RefreshFilePresence();
        }

        RefreshFilteredDownloads();
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
        RefreshDestinationConflictPreview();
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
        RefreshDestinationConflictPreview();
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
        RefreshDestinationConflictPreview();
    }

    private OrganizationSettings BuildOrganizationSettings()
        => new(
            SelectedDuplicateUrlBehavior,
            ComputeContentHashes,
            DestinationRules.ToArray(),
            SavedSearches.ToArray());
}
