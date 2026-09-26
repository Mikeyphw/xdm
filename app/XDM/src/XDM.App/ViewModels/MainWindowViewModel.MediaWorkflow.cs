using System.Collections.ObjectModel;
using System.Globalization;
using System.Text.Json;
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

    private string? _lastSuggestedConversionDestination;
    private readonly Dictionary<string, string> _goMediaCaptureIdsBySource = new(StringComparer.Ordinal);
    private readonly Dictionary<string, (string? Video, string? Audio, string? Subtitle)> _goMediaSelections = new(StringComparer.Ordinal);
    private string? _currentGoMediaCaptureId;
    private bool _applyingGoMediaProjection;

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

            await SubmitMediaCatalogToGoAsync(catalog, current.Metadata, current.SourcePage, current.Browser).ConfigureAwait(false);
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
    {
        RefreshMediaSelectionSummary();
        QueueGoMediaSelection();
    }

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
        _goMediaCaptureIdsBySource.TryGetValue(entry.Catalog.Source.AbsoluteUri, out _currentGoMediaCaptureId);
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
        if (_desktopGo.HasMediaProjection)
        {
            ApplySelectionFromGoProjection();
        }
        else
        {
            ApplyMediaSelectionPreferences();
        }
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
        _currentGoMediaCaptureId = null;
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
            QueueGoMediaSelection();
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
        if (_desktopGo.HasMediaProjection)
        {
            // Go owns execution selection. Desktop quality controls only format/display Go projections.
            ApplySelectionFromGoProjection();
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

    private void QueueGoMediaSelection()
    {
        if (_applyingGoMediaProjection || !_desktopGo.HasMediaProjection || string.IsNullOrWhiteSpace(_currentGoMediaCaptureId))
        {
            return;
        }
        _ = PublishGoMediaSelectionAsync();
    }

    private async Task PublishGoMediaSelectionAsync()
    {
        if (string.IsNullOrWhiteSpace(_currentGoMediaCaptureId))
        {
            return;
        }
        try
        {
            await _desktopGo.SelectMediaAsync(
                _currentGoMediaCaptureId,
                SelectedMediaVideoFormat?.Id,
                SelectedMediaAudioFormat?.Id,
                MediaSubtitleFormats.FirstOrDefault(static format => format.IsSelected)?.Id).ConfigureAwait(false);
        }
        catch (InvalidOperationException exception)
        {
            _dispatcher.Post(() => MediaSelectionSummary = $"Go rejected media selection: {exception.Message}");
        }
    }

    private void ApplySelectionFromGoProjection()
    {
        if (string.IsNullOrWhiteSpace(_currentGoMediaCaptureId)
            || !_goMediaSelections.TryGetValue(_currentGoMediaCaptureId, out var selection))
        {
            RefreshMediaSelectionSummary();
            return;
        }
        _applyingGoMediaProjection = true;
        try
        {
            SelectedMediaVideoFormat = MediaVideoFormats.FirstOrDefault(item => string.Equals(item.Id, selection.Video, StringComparison.Ordinal));
            SelectedMediaAudioFormat = MediaAudioFormats.FirstOrDefault(item => string.Equals(item.Id, selection.Audio, StringComparison.Ordinal));
            foreach (MediaFormatViewModel subtitle in MediaSubtitleFormats)
            {
                subtitle.IsSelected = string.Equals(subtitle.Id, selection.Subtitle, StringComparison.Ordinal);
            }
        }
        finally
        {
            _applyingGoMediaProjection = false;
        }
        RefreshMediaSelectionSummary();
    }

    private async Task<string> SubmitMediaCatalogToGoAsync(
        MediaCatalog catalog,
        MediaRequestMetadata metadata,
        string? sourcePage,
        string? browser,
        string? sessionId = null)
    {
        string captureId = "desktop_" + Guid.NewGuid().ToString("N");
        string fileName = SanitizeMediaFileName(catalog.Title) + ".mp4";
        string kind = catalog.Kind switch
        {
            MediaKind.Hls => "HlsPlaylist",
            MediaKind.Dash => "DashManifest",
            MediaKind.DirectFile => "DirectFile",
            MediaKind.ExternalProvider => "ExternalProvider",
            _ => "Media"
        };
        string? mimeType = catalog.Kind switch
        {
            MediaKind.Hls => "application/vnd.apple.mpegurl",
            MediaKind.Dash => "application/dash+xml",
            _ => null
        };
        object captureRecord = new
        {
            id = captureId,
            source_url = catalog.Source.AbsoluteUri,
            page_url = sourcePage,
            title = catalog.Title,
            kind,
            mime_type = mimeType,
            container = catalog.Formats.FirstOrDefault()?.Container,
            codecs = catalog.Formats.FirstOrDefault()?.Codecs,
            duration_ms = catalog.Duration is TimeSpan duration ? (long?)duration.TotalMilliseconds : null,
            file_name = fileName,
            manifest_is_live = catalog.IsLive,
            manifest_protected = catalog.Formats.Any(static format => format.IsEncrypted),
            row_revision = 1L
        };
        object[] variants = catalog.Formats.Select(format =>
        {
            string id = captureId + ":" + format.Id;
            _desktopGo.RegisterMediaVariant(id, new DesktopMediaRequestHandoff(
                format.ManifestUri,
                metadata.Headers,
                metadata.Cookie,
                metadata.Referer,
                metadata.UserAgent));
            string variantKind = format.StreamKind switch
            {
                MediaStreamKind.Muxed => "Primary",
                MediaStreamKind.Video => "Video",
                MediaStreamKind.Audio => "Audio",
                MediaStreamKind.Subtitle => "Subtitle",
                _ => "Primary"
            };
            return (object)new
            {
                id,
                capture_id = captureId,
                url = format.ManifestUri.AbsoluteUri,
                kind = variantKind,
                width = format.Width,
                height = format.Height,
                bitrate_bits_per_second = format.Bandwidth,
                codecs = format.Codecs,
                language = format.Language,
                group_id = format.AudioGroupId ?? format.SubtitleGroupId
            };
        }).ToArray();
        _desktopGo.RegisterMediaCapture(captureId, new DesktopMediaRequestHandoff(
            catalog.Source,
            metadata.Headers,
            metadata.Cookie,
            metadata.Referer,
            metadata.UserAgent));

        Uri pageUri = Uri.TryCreate(sourcePage, UriKind.Absolute, out Uri? parsedPage) ? parsedPage : catalog.Source;
        string origin = pageUri.GetLeftPart(UriPartial.Authority);
        object[] safeHeaders = (metadata.Headers ?? new Dictionary<string, string>())
            .Where(static pair => !IsSensitiveCaptureHeader(pair.Key))
            .Select(static pair => (object)new { name = pair.Key, value = pair.Value })
            .ToArray();
        string requestId = "media_" + Guid.NewGuid().ToString("N");
        JsonElement payload = JsonSerializer.SerializeToElement(new
        {
            client_request_id = requestId,
            capture_record = captureRecord,
            variants,
            envelope = new
            {
                version = 1,
                request = new { url = catalog.Source.AbsoluteUri, method = "GET", headers = safeHeaders },
                page = new
                {
                    page_url = pageUri.AbsoluteUri,
                    frame_origin = origin,
                    session_id = string.IsNullOrWhiteSpace(sessionId) ? "desktop-" + Guid.NewGuid().ToString("N") : sessionId,
                    document_generation = Math.Max(1L, DateTimeOffset.UtcNow.ToUnixTimeMilliseconds())
                },
                response = new
                {
                    status_code = 200,
                    content_type = mimeType ?? string.Empty,
                    observed_at = DateTimeOffset.UtcNow
                },
                media_hints = new[] { new { kind = "manifest", value = catalog.Kind.ToString().ToLowerInvariant() } },
                credential_scope = new { origin = catalog.Source.GetLeftPart(UriPartial.Authority), path_prefix = "/", @ref = "desktop-handoff:" + captureId }
            }
        });
        await _desktopGo.CaptureMediaAsync(payload, requestId).ConfigureAwait(false);
        _dispatcher.Post(() => OperationMessage = $"Go media graph accepted {catalog.Formats.Count} variant(s) from {browser ?? "desktop"}.");
        return captureId;
    }

    private static bool IsSensitiveCaptureHeader(string name)
    {
        string value = name.Trim().ToLowerInvariant();
        return value is "authorization" or "cookie" or "proxy-authorization" or "x-api-key" or "api-key"
            || value.Contains("token", StringComparison.Ordinal)
            || value.EndsWith("-key", StringComparison.Ordinal);
    }

    private void OnDesktopGoMediaProjectionChanged(object? sender, DesktopGoMediaProjectionEventArgs eventArgs)
    {
        JsonElement projection = eventArgs.Projection.Clone();
        if (_dispatcher.CheckAccess())
        {
            ApplyDesktopGoMediaProjection(projection);
        }
        else
        {
            _dispatcher.Post(() => ApplyDesktopGoMediaProjection(projection));
        }
    }

    private void ApplyDesktopGoMediaProjection(JsonElement projection)
    {
        string? previousCapture = _currentGoMediaCaptureId;
        _applyingGoMediaProjection = true;
        try
        {
            _goMediaSelections.Clear();
            if (projection.TryGetProperty("selections", out JsonElement selections) && selections.ValueKind == JsonValueKind.Array)
            {
                foreach (JsonElement selection in selections.EnumerateArray())
                {
                    string captureId = selection.GetProperty("capture_id").GetString() ?? string.Empty;
                    _goMediaSelections[captureId] = (
                        selection.TryGetProperty("video_variant_id", out JsonElement video) ? video.GetString() : null,
                        selection.TryGetProperty("audio_variant_id", out JsonElement audio) ? audio.GetString() : null,
                        selection.TryGetProperty("subtitle_variant_id", out JsonElement subtitle) ? subtitle.GetString() : null);
                }
            }
            Dictionary<string, List<JsonElement>> variantsByCapture = new(StringComparer.Ordinal);
            if (projection.TryGetProperty("variants", out JsonElement variants) && variants.ValueKind == JsonValueKind.Array)
            {
                foreach (JsonElement variant in variants.EnumerateArray())
                {
                    string captureId = variant.GetProperty("capture_id").GetString() ?? string.Empty;
                    if (!variantsByCapture.TryGetValue(captureId, out List<JsonElement>? list))
                    {
                        list = [];
                        variantsByCapture[captureId] = list;
                    }
                    list.Add(variant.Clone());
                }
            }

            MediaInbox.Clear();
            _goMediaCaptureIdsBySource.Clear();
            if (projection.TryGetProperty("captures", out JsonElement captures) && captures.ValueKind == JsonValueKind.Array)
            {
                foreach (JsonElement capture in captures.EnumerateArray())
                {
                    string captureId = capture.GetProperty("id").GetString() ?? string.Empty;
                    if (!Uri.TryCreate(capture.GetProperty("source_url").GetString(), UriKind.Absolute, out Uri? source))
                    {
                        continue;
                    }
                    List<MediaFormat> formats = [];
                    if (variantsByCapture.TryGetValue(captureId, out List<JsonElement>? projectedVariants))
                    {
                        foreach (JsonElement variant in projectedVariants)
                        {
                            if (!Uri.TryCreate(variant.GetProperty("url").GetString(), UriKind.Absolute, out Uri? url))
                            {
                                continue;
                            }
                            string variantKind = variant.GetProperty("kind").GetString() ?? "Primary";
                            MediaStreamKind streamKind = variantKind.ToLowerInvariant() switch
                            {
                                "audio" => MediaStreamKind.Audio,
                                "subtitle" => MediaStreamKind.Subtitle,
                                "video" => MediaStreamKind.Video,
                                _ => MediaStreamKind.Muxed
                            };
                            formats.Add(new MediaFormat(
                                variant.GetProperty("id").GetString() ?? Guid.NewGuid().ToString("N"),
                                streamKind,
                                url,
                                null,
                                variant.TryGetProperty("codecs", out JsonElement codecs) ? codecs.GetString() : null,
                                variant.TryGetProperty("bitrate_bits_per_second", out JsonElement bitrate) && bitrate.TryGetInt64(out long bitrateValue) ? bitrateValue : null,
                                variant.TryGetProperty("width", out JsonElement width) && width.TryGetInt32(out int widthValue) ? widthValue : null,
                                variant.TryGetProperty("height", out JsonElement height) && height.TryGetInt32(out int heightValue) ? heightValue : null,
                                null,
                                variant.TryGetProperty("language", out JsonElement language) ? language.GetString() : null,
                                variantKind,
                                false,
                                capture.TryGetProperty("manifest_protected", out JsonElement protectedValue) && protectedValue.ValueKind == JsonValueKind.True,
                                AudioGroupId: variant.TryGetProperty("group_id", out JsonElement group) ? group.GetString() : null));
                        }
                    }
                    string kind = capture.GetProperty("kind").GetString() ?? string.Empty;
                    MediaKind mediaKind = kind.Contains("hls", StringComparison.OrdinalIgnoreCase) ? MediaKind.Hls
                        : kind.Contains("dash", StringComparison.OrdinalIgnoreCase) ? MediaKind.Dash
                        : MediaKind.DirectFile;
                    TimeSpan? duration = capture.TryGetProperty("duration_ms", out JsonElement durationElement)
                        && durationElement.TryGetInt64(out long durationMs) && durationMs > 0
                        ? TimeSpan.FromMilliseconds(durationMs)
                        : null;
                    MediaCatalog catalog = new(
                        source,
                        mediaKind,
                        capture.TryGetProperty("title", out JsonElement title) ? title.GetString() ?? "Detected media" : "Detected media",
                        capture.TryGetProperty("manifest_is_live", out JsonElement live) && live.ValueKind == JsonValueKind.True,
                        formats,
                        "Projected by the Go media graph.",
                        "Go media graph",
                        duration);
                    _goMediaCaptureIdsBySource[source.AbsoluteUri] = captureId;
                    AddMediaInboxEntry(catalog, MediaRequestMetadata.Empty, null, "Go", select: string.Equals(captureId, previousCapture, StringComparison.Ordinal));
                }
            }
            if (SelectedMediaInboxItem is null && MediaInbox.Count > 0)
            {
                SelectedMediaInboxItem = MediaInbox[0];
            }
            ApplySelectionFromGoProjection();
        }
        finally
        {
            _applyingGoMediaProjection = false;
        }
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
