using System.Collections.ObjectModel;
using System.Globalization;
using CommunityToolkit.Mvvm.ComponentModel;
using CommunityToolkit.Mvvm.Input;
using XDM.Media;

namespace XDM.App.ViewModels;

public partial class MainWindowViewModel
{
    private static readonly MediaQualityOption[] MediaQualityOptionDefinitions =
    [
        new("best", "Best available"),
        new("2160", "Up to 2160p", 2160),
        new("1440", "Up to 1440p", 1440),
        new("1080", "Up to 1080p", 1080),
        new("720", "Up to 720p", 720),
        new("480", "Up to 480p", 480),
        new("smallest", "Smallest video", PreferSmallest: true),
        new("audio", "Audio only", AudioOnly: true)
    ];

    private MediaRequestMetadata _currentMediaMetadata = MediaRequestMetadata.Empty;

    public ObservableCollection<MediaInboxItemViewModel> MediaInbox { get; } = [];

    public ObservableCollection<MediaFormatViewModel> MediaAllFormats { get; } = [];

    public ObservableCollection<string> MediaAudioLanguageOptions { get; } = ["Any"];

    public ObservableCollection<string> MediaSubtitleLanguageOptions { get; } = ["Default", "None"];

    public IReadOnlyList<MediaQualityOption> MediaQualityOptions { get; } = MediaQualityOptionDefinitions;

    [ObservableProperty]
    [NotifyPropertyChangedFor(nameof(HasSelectedMediaInboxItem))]
    [NotifyPropertyChangedFor(nameof(HasNoSelectedMediaInboxItem))]
    private MediaInboxItemViewModel? selectedMediaInboxItem;

    [ObservableProperty]
    private MediaQualityOption selectedMediaQuality = MediaQualityOptionDefinitions[0];

    [ObservableProperty]
    private string selectedMediaAudioLanguage = "Any";

    [ObservableProperty]
    private string selectedMediaSubtitleLanguage = "Default";

    [ObservableProperty]
    private string mediaLiveMaximumSizeMb = "0";

    [ObservableProperty]
    private string mediaSelectionSummary = "Select a detected item to build a download plan.";

    [ObservableProperty]
    private string mediaEstimatedSize = "Size estimate unavailable";

    public bool HasSelectedMediaInboxItem => SelectedMediaInboxItem is not null;

    public bool HasNoSelectedMediaInboxItem => SelectedMediaInboxItem is null;

    public bool HasMediaInboxItems => MediaInbox.Count > 0;

    public bool HasNoMediaInboxItems => MediaInbox.Count == 0;

    public bool IsSelectedMediaLive => _currentMediaCatalog?.IsLive == true;

    public string MediaInboxCountSummary
        => MediaInbox.Count == 1 ? "1 detected item" : $"{MediaInbox.Count} detected items";

    [RelayCommand]
    private void RemoveSelectedMediaInboxItem()
    {
        if (SelectedMediaInboxItem is null)
        {
            return;
        }

        int index = MediaInbox.IndexOf(SelectedMediaInboxItem);
        MediaInbox.Remove(SelectedMediaInboxItem);
        SelectedMediaInboxItem = MediaInbox.Count == 0
            ? null
            : MediaInbox[Math.Clamp(index, 0, MediaInbox.Count - 1)];
        NotifyMediaInboxChanged();
    }

    [RelayCommand]
    private void ClearMediaInbox()
    {
        MediaInbox.Clear();
        SelectedMediaInboxItem = null;
        ClearMediaWorkspace();
        NotifyMediaInboxChanged();
    }

    [RelayCommand]
    private async Task RefreshSelectedMediaInboxAsync()
    {
        if (SelectedMediaInboxItem is null)
        {
            return;
        }

        MediaInboxItemViewModel current = SelectedMediaInboxItem;
        MediaCatalogSummary = "Refreshing media variants…";
        try
        {
            MediaCatalog catalog = await _mediaCatalogService.GetCatalogAsync(
                current.Catalog.Source,
                current.Metadata,
                CancellationToken.None);
            if (catalog.Kind == MediaKind.Unknown || catalog.Formats.Count == 0)
            {
                MediaCatalogSummary = catalog.Description;
                return;
            }

            AddMediaInboxEntry(catalog, current.Metadata, current.SourcePage, current.Browser, select: true);
        }
        catch (HttpRequestException exception)
        {
            MediaCatalogSummary = $"Media refresh failed: {exception.Message}";
        }
        catch (InvalidDataException exception)
        {
            MediaCatalogSummary = $"Media refresh failed: {exception.Message}";
        }
        catch (InvalidOperationException exception)
        {
            MediaCatalogSummary = $"Media refresh failed: {exception.Message}";
        }
        catch (NotSupportedException exception)
        {
            MediaCatalogSummary = $"Media refresh failed: {exception.Message}";
        }
        catch (UnauthorizedAccessException exception)
        {
            MediaCatalogSummary = $"Media refresh failed: {exception.Message}";
        }
    }

    partial void OnSelectedMediaInboxItemChanged(MediaInboxItemViewModel? value)
    {
        if (value is null)
        {
            ClearMediaWorkspace();
            return;
        }

        LoadMediaInboxEntry(value);
    }

    partial void OnSelectedMediaQualityChanged(MediaQualityOption value)
        => ApplyMediaSelectionPreferences();

    partial void OnSelectedMediaAudioLanguageChanged(string value)
        => ApplyMediaSelectionPreferences();

    partial void OnSelectedMediaSubtitleLanguageChanged(string value)
        => ApplyMediaSelectionPreferences();

    partial void OnSelectedMediaAudioFormatChanged(MediaFormatViewModel? value)
        => RefreshMediaSelectionSummary();

    partial void OnMediaLiveDurationMinutesChanged(string value)
        => RefreshMediaSelectionSummary();

    partial void OnMediaLiveMaximumSizeMbChanged(string value)
        => RefreshMediaSelectionSummary();

    private void AddMediaInboxEntry(
        MediaCatalog catalog,
        MediaRequestMetadata metadata,
        string? sourcePage,
        string? browser,
        bool select)
    {
        MediaInboxItemViewModel entry = new(catalog, metadata, sourcePage, browser, DateTimeOffset.UtcNow);
        MediaInboxItemViewModel? existing = MediaInbox.FirstOrDefault(item =>
            string.Equals(item.Id, entry.Id, StringComparison.Ordinal));
        int insertionIndex = 0;
        if (existing is not null)
        {
            insertionIndex = MediaInbox.IndexOf(existing);
            MediaInbox.Remove(existing);
        }

        MediaInbox.Insert(Math.Clamp(insertionIndex, 0, MediaInbox.Count), entry);
        while (MediaInbox.Count > 100)
        {
            MediaInbox.RemoveAt(MediaInbox.Count - 1);
        }

        if (select)
        {
            SelectedMediaInboxItem = entry;
        }

        NotifyMediaInboxChanged();
    }

    private void LoadMediaInboxEntry(MediaInboxItemViewModel entry)
    {
        _currentMediaCatalog = entry.Catalog;
        _currentMediaMetadata = entry.Metadata;
        OnPropertyChanged(nameof(IsSelectedMediaLive));
        MediaSourceUrl = entry.Catalog.Source.AbsoluteUri;
        DetachMediaFormatHandlers();
        MediaAllFormats.Clear();
        MediaVideoFormats.Clear();
        MediaAudioFormats.Clear();
        MediaSubtitleFormats.Clear();
        foreach (MediaFormat format in entry.Catalog.Formats)
        {
            MediaFormatViewModel viewModel = new(format);
            viewModel.PropertyChanged += OnMediaFormatPropertyChanged;
            MediaAllFormats.Add(viewModel);
            switch (format.StreamKind)
            {
                case MediaStreamKind.Muxed:
                case MediaStreamKind.Video:
                    MediaVideoFormats.Add(viewModel);
                    break;
                case MediaStreamKind.Audio:
                    MediaAudioFormats.Add(viewModel);
                    break;
                case MediaStreamKind.Subtitle:
                    MediaSubtitleFormats.Add(viewModel);
                    break;
            }
        }

        ReplaceLanguageOptions();
        ApplyMediaSelectionPreferences();
        string duration = entry.Catalog.Duration is TimeSpan catalogDuration
            ? $" • {FormatDuration(catalogDuration)}"
            : string.Empty;
        MediaCatalogSummary = $"{entry.Catalog.Title} • {entry.ProtocolLabel} • {entry.StatusLabel} • {entry.Catalog.Formats.Count} format(s){duration} • {entry.Catalog.Provider}";
        if (string.IsNullOrWhiteSpace(MediaDestinationFile) || MediaDestinationFile == "video.mp4")
        {
            MediaDestinationFile = $"{SanitizeMediaFileName(entry.Catalog.Title)}.mp4";
        }
    }

    private void ClearMediaWorkspace()
    {
        _currentMediaCatalog = null;
        _currentMediaMetadata = MediaRequestMetadata.Empty;
        OnPropertyChanged(nameof(IsSelectedMediaLive));
        DetachMediaFormatHandlers();
        MediaAllFormats.Clear();
        MediaVideoFormats.Clear();
        MediaAudioFormats.Clear();
        MediaSubtitleFormats.Clear();
        SelectedMediaVideoFormat = null;
        SelectedMediaAudioFormat = null;
        MediaCatalogSummary = "Select a detected item or discover a media URL.";
        MediaSelectionSummary = "Select a detected item to build a download plan.";
        MediaEstimatedSize = "Size estimate unavailable";
    }

    private void OnMediaFormatPropertyChanged(object? sender, System.ComponentModel.PropertyChangedEventArgs eventArgs)
    {
        if (eventArgs.PropertyName == nameof(MediaFormatViewModel.IsSelected))
        {
            RefreshMediaSelectionSummary();
        }
    }

    private void DetachMediaFormatHandlers()
    {
        foreach (MediaFormatViewModel format in MediaAllFormats)
        {
            format.PropertyChanged -= OnMediaFormatPropertyChanged;
        }
    }

    private void ReplaceLanguageOptions()
    {
        string previousAudio = SelectedMediaAudioLanguage;
        string previousSubtitle = SelectedMediaSubtitleLanguage;
        MediaAudioLanguageOptions.Clear();
        MediaAudioLanguageOptions.Add("Any");
        foreach (string language in MediaAudioFormats
            .Select(static format => format.Format.Language)
            .Where(static language => !string.IsNullOrWhiteSpace(language))
            .Cast<string>()
            .Distinct(StringComparer.OrdinalIgnoreCase)
            .Order(StringComparer.OrdinalIgnoreCase))
        {
            MediaAudioLanguageOptions.Add(language);
        }

        MediaSubtitleLanguageOptions.Clear();
        MediaSubtitleLanguageOptions.Add("Default");
        MediaSubtitleLanguageOptions.Add("None");
        foreach (string language in MediaSubtitleFormats
            .Select(static format => format.Format.Language)
            .Where(static language => !string.IsNullOrWhiteSpace(language))
            .Cast<string>()
            .Distinct(StringComparer.OrdinalIgnoreCase)
            .Order(StringComparer.OrdinalIgnoreCase))
        {
            MediaSubtitleLanguageOptions.Add(language);
        }

        SelectedMediaAudioLanguage = MediaAudioLanguageOptions.Contains(previousAudio, StringComparer.OrdinalIgnoreCase)
            ? previousAudio
            : "Any";
        SelectedMediaSubtitleLanguage = MediaSubtitleLanguageOptions.Contains(previousSubtitle, StringComparer.OrdinalIgnoreCase)
            ? previousSubtitle
            : "Default";
    }

    private void ApplyMediaSelectionPreferences()
    {
        if (_currentMediaCatalog is null)
        {
            return;
        }

        MediaSelectionResult selection = MediaSelectionPolicy.Select(
            _currentMediaCatalog,
            new MediaSelectionRequest(
                SelectedMediaQuality.MaximumHeight,
                SelectedMediaQuality.AudioOnly,
                SelectedMediaQuality.PreferSmallest,
                SelectedMediaAudioLanguage,
                SelectedMediaSubtitleLanguage));
        SelectedMediaVideoFormat = selection.Video is null
            ? null
            : MediaVideoFormats.FirstOrDefault(format => string.Equals(format.Id, selection.Video.Id, StringComparison.Ordinal));
        SelectedMediaAudioFormat = selection.Audio is null
            ? null
            : MediaAudioFormats.FirstOrDefault(format => string.Equals(format.Id, selection.Audio.Id, StringComparison.Ordinal));
        HashSet<string> subtitleIds = selection.Subtitles
            .Select(static format => format.Id)
            .ToHashSet(StringComparer.Ordinal);
        foreach (MediaFormatViewModel subtitle in MediaSubtitleFormats)
        {
            subtitle.IsSelected = subtitleIds.Contains(subtitle.Id);
        }

        RefreshMediaSelectionSummary();
    }

    private void RefreshMediaSelectionSummary()
    {
        foreach (MediaFormatViewModel format in MediaAllFormats)
        {
            format.IsChosen = ReferenceEquals(format, SelectedMediaVideoFormat)
                || ReferenceEquals(format, SelectedMediaAudioFormat)
                || format.IsSelected;
        }

        if (_currentMediaCatalog is null)
        {
            MediaSelectionSummary = "Select a detected item to build a download plan.";
            MediaEstimatedSize = "Size estimate unavailable";
            return;
        }

        List<string> parts = [];
        if (SelectedMediaVideoFormat is not null)
        {
            parts.Add(SelectedMediaVideoFormat.Quality);
        }
        else
        {
            parts.Add("Audio only");
        }

        if (SelectedMediaAudioFormat is not null)
        {
            parts.Add($"{SelectedMediaAudioFormat.Language} audio");
        }
        else if (SelectedMediaVideoFormat?.Format.StreamKind == MediaStreamKind.Muxed)
        {
            parts.Add("muxed audio");
        }

        int subtitleCount = MediaSubtitleFormats.Count(static format => format.IsSelected);
        parts.Add(subtitleCount == 0 ? "no subtitles" : $"{subtitleCount} subtitle(s)");
        MediaSelectionSummary = string.Join(" • ", parts);

        TimeSpan? duration = _currentMediaCatalog.IsLive
            ? ParseLiveDuration(MediaLiveDurationMinutes)
            : _currentMediaCatalog.Duration;
        long? estimatedBytes = MediaSizeEstimator.EstimateBytes(
            duration,
            SelectedMediaVideoFormat?.Format,
            SelectedMediaAudioFormat?.Format);
        MediaEstimatedSize = estimatedBytes is long estimate && duration is TimeSpan knownDuration
            ? $"Estimated {FormatMediaBytes(estimate)} for {FormatDuration(knownDuration)}"
            : "Size estimate unavailable for this source";

        long? maximumBytes = ParseMediaMaximumBytes();
        if (maximumBytes is long limit)
        {
            MediaEstimatedSize += $" • hard limit {FormatMediaBytes(limit)}";
        }
    }

    private long? ParseMediaMaximumBytes()
    {
        if (!double.TryParse(
            MediaLiveMaximumSizeMb,
            NumberStyles.Float,
            CultureInfo.InvariantCulture,
            out double megabytes)
            || !double.IsFinite(megabytes)
            || megabytes <= 0)
        {
            return null;
        }

        double bytes = megabytes * 1024d * 1024d;
        return bytes >= 10d * 1024 * 1024 * 1024 * 1024
            ? 10L * 1024 * 1024 * 1024 * 1024
            : checked((long)bytes);
    }

    private static string FormatMediaBytes(double bytes)
    {
        string[] units = ["B", "KiB", "MiB", "GiB", "TiB"];
        int unit = 0;
        while (bytes >= 1024 && unit < units.Length - 1)
        {
            bytes /= 1024;
            unit++;
        }

        return $"{bytes.ToString("0.#", CultureInfo.CurrentCulture)} {units[unit]}";
    }

    private static string FormatDuration(TimeSpan duration)
        => duration.TotalHours >= 1
            ? $"{(int)duration.TotalHours}:{duration.Minutes:D2}:{duration.Seconds:D2}"
            : $"{duration.Minutes}:{duration.Seconds:D2}";

    private void NotifyMediaInboxChanged()
    {
        OnPropertyChanged(nameof(HasMediaInboxItems));
        OnPropertyChanged(nameof(HasNoMediaInboxItems));
        OnPropertyChanged(nameof(MediaInboxCountSummary));
    }
}
