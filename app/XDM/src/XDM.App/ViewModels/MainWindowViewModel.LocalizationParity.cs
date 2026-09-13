using System.Collections.ObjectModel;
using CommunityToolkit.Mvvm.ComponentModel;
using XDM.Core.Downloads;
using XDM.Core.Product;
using XDM.Core.Settings;
using XDM.DownloadEngine;

namespace XDM.App.ViewModels;

public partial class MainWindowViewModel
{
    private bool _localizedChoiceSelectionSyncing;

    public ObservableCollection<LocalizedChoice<DuplicateFileBehavior>> DuplicateBehaviorChoices { get; } = [];

    public ObservableCollection<LocalizedChoice<DuplicateUrlBehavior>> DuplicateUrlBehaviorChoices { get; } = [];

    public ObservableCollection<LocalizedChoice<ProxyMode>> ProxyModeChoices { get; } = [];

    public ObservableCollection<LocalizedChoice<ProxyAuthenticationMode>> ProxyAuthenticationModeChoices { get; } = [];

    public ObservableCollection<LocalizedChoice<Aria2ConnectionMode>> Aria2ConnectionModeChoices { get; } = [];

    public ObservableCollection<LocalizedChoice<UpdateChannel>> UpdateChannelChoices { get; } = [];

    [ObservableProperty]
    private LocalizedChoice<DuplicateFileBehavior>? selectedDuplicateBehaviorChoice;

    [ObservableProperty]
    private LocalizedChoice<DuplicateUrlBehavior>? selectedDuplicateUrlBehaviorChoice;

    [ObservableProperty]
    private LocalizedChoice<ProxyMode>? selectedProxyModeChoice;

    [ObservableProperty]
    private LocalizedChoice<ProxyAuthenticationMode>? selectedProxyAuthenticationModeChoice;

    [ObservableProperty]
    private LocalizedChoice<Aria2ConnectionMode>? selectedAria2ConnectionModeChoice;

    [ObservableProperty]
    private LocalizedChoice<UpdateChannel>? selectedUpdateChannelChoice;

    public bool CanPauseSelectedTransfer => SelectedDownload?.CanPause == true;

    public bool CanResumeSelectedTransfer => SelectedDownload?.CanResume == true;

    public bool CanCancelSelectedTransfer => SelectedDownload?.CanCancel == true;

    private void InitializeLocalizedChoices()
    {
        PopulateLocalizedChoices(
            DuplicateBehaviorChoices,
            static value => value switch
            {
                DuplicateFileBehavior.AutoRename => "choice_duplicate_autorename",
                DuplicateFileBehavior.Overwrite => "choice_duplicate_overwrite",
                DuplicateFileBehavior.Skip => "choice_duplicate_skip",
                _ => $"choice_duplicate_{value.ToString().ToLowerInvariant()}"
            });
        PopulateLocalizedChoices(
            DuplicateUrlBehaviorChoices,
            static value => value switch
            {
                DuplicateUrlBehavior.FocusExisting => "choice_duplicateurl_focusexisting",
                DuplicateUrlBehavior.Allow => "choice_duplicateurl_allow",
                DuplicateUrlBehavior.Reject => "choice_duplicateurl_reject",
                _ => $"choice_duplicateurl_{value.ToString().ToLowerInvariant()}"
            });
        PopulateLocalizedChoices(
            ProxyModeChoices,
            static value => value switch
            {
                ProxyMode.System => "choice_proxy_system",
                ProxyMode.None => "choice_proxy_none",
                ProxyMode.Manual => "choice_proxy_manual",
                ProxyMode.AutomaticScript => "choice_proxy_automaticscript",
                _ => $"choice_proxy_{value.ToString().ToLowerInvariant()}"
            });
        PopulateLocalizedChoices(
            ProxyAuthenticationModeChoices,
            static value => value switch
            {
                ProxyAuthenticationMode.None => "choice_proxyauth_none",
                ProxyAuthenticationMode.Integrated => "choice_proxyauth_integrated",
                ProxyAuthenticationMode.Basic => "choice_proxyauth_basic",
                _ => $"choice_proxyauth_{value.ToString().ToLowerInvariant()}"
            });
        PopulateLocalizedChoices(
            Aria2ConnectionModeChoices,
            static value => value switch
            {
                Aria2ConnectionMode.ManagedProcess => "choice_aria2_managedprocess",
                Aria2ConnectionMode.ExternalRpc => "choice_aria2_externalrpc",
                _ => $"choice_aria2_{value.ToString().ToLowerInvariant()}"
            });
        PopulateLocalizedChoices(
            UpdateChannelChoices,
            static value => value switch
            {
                UpdateChannel.Stable => "choice_update_stable",
                UpdateChannel.Nightly => "choice_update_nightly",
                _ => $"choice_update_{value.ToString().ToLowerInvariant()}"
            });
        SyncLocalizedChoicesFromPrimitiveValues();
    }

    private void PopulateLocalizedChoices<T>(
        ObservableCollection<LocalizedChoice<T>> target,
        Func<T, string> keySelector)
        where T : struct, Enum
    {
        target.Clear();
        foreach (T value in Enum.GetValues<T>())
        {
            target.Add(new LocalizedChoice<T>(value, keySelector(value), _localization));
        }
    }

    private void RefreshLocalizedChoices()
    {
        foreach (LocalizedChoice<DuplicateFileBehavior> choice in DuplicateBehaviorChoices)
        {
            choice.Refresh();
        }

        foreach (LocalizedChoice<DuplicateUrlBehavior> choice in DuplicateUrlBehaviorChoices)
        {
            choice.Refresh();
        }

        foreach (LocalizedChoice<ProxyMode> choice in ProxyModeChoices)
        {
            choice.Refresh();
        }

        foreach (LocalizedChoice<ProxyAuthenticationMode> choice in ProxyAuthenticationModeChoices)
        {
            choice.Refresh();
        }

        foreach (LocalizedChoice<Aria2ConnectionMode> choice in Aria2ConnectionModeChoices)
        {
            choice.Refresh();
        }

        foreach (LocalizedChoice<UpdateChannel> choice in UpdateChannelChoices)
        {
            choice.Refresh();
        }
    }

    private void SyncLocalizedChoicesFromPrimitiveValues()
    {
        SelectChoice(DuplicateBehaviorChoices, SelectedDuplicateBehavior, choice => SelectedDuplicateBehaviorChoice = choice);
        SelectChoice(DuplicateUrlBehaviorChoices, SelectedDuplicateUrlBehavior, choice => SelectedDuplicateUrlBehaviorChoice = choice);
        SelectChoice(ProxyModeChoices, SelectedProxyMode, choice => SelectedProxyModeChoice = choice);
        SelectChoice(ProxyAuthenticationModeChoices, SelectedProxyAuthenticationMode, choice => SelectedProxyAuthenticationModeChoice = choice);
        SelectChoice(Aria2ConnectionModeChoices, SelectedAria2ConnectionMode, choice => SelectedAria2ConnectionModeChoice = choice);
        SelectChoice(UpdateChannelChoices, SelectedUpdateChannel, choice => SelectedUpdateChannelChoice = choice);
    }

    private void SyncDuplicateBehaviorChoiceFromValue()
        => SelectChoice(DuplicateBehaviorChoices, SelectedDuplicateBehavior, choice => SelectedDuplicateBehaviorChoice = choice);

    private void SelectChoice<T>(
        ObservableCollection<LocalizedChoice<T>> choices,
        string value,
        Action<LocalizedChoice<T>?> assign)
        where T : struct, Enum
    {
        LocalizedChoice<T>? selected = choices.FirstOrDefault(choice => string.Equals(
            choice.Value.ToString(),
            value,
            StringComparison.OrdinalIgnoreCase));
        SetChoice(selected, assign);
    }

    private void SelectChoice<T>(
        ObservableCollection<LocalizedChoice<T>> choices,
        T value,
        Action<LocalizedChoice<T>?> assign)
        where T : struct, Enum
    {
        LocalizedChoice<T>? selected = choices.FirstOrDefault(choice => EqualityComparer<T>.Default.Equals(choice.Value, value));
        SetChoice(selected, assign);
    }

    private void SetChoice<T>(LocalizedChoice<T>? selected, Action<LocalizedChoice<T>?> assign)
        where T : struct, Enum
    {
        if (_localizedChoiceSelectionSyncing)
        {
            assign(selected);
            return;
        }

        _localizedChoiceSelectionSyncing = true;
        try
        {
            assign(selected);
        }
        finally
        {
            _localizedChoiceSelectionSyncing = false;
        }
    }

    partial void OnSelectedDuplicateBehaviorChoiceChanged(LocalizedChoice<DuplicateFileBehavior>? value)
    {
        if (value is null || _localizedChoiceSelectionSyncing)
        {
            return;
        }

        _localizedChoiceSelectionSyncing = true;
        try
        {
            SelectedDuplicateBehavior = value.Value.ToString();
        }
        finally
        {
            _localizedChoiceSelectionSyncing = false;
        }
    }

    partial void OnSelectedDuplicateUrlBehaviorChoiceChanged(LocalizedChoice<DuplicateUrlBehavior>? value)
    {
        if (value is null || _localizedChoiceSelectionSyncing)
        {
            return;
        }

        _localizedChoiceSelectionSyncing = true;
        try
        {
            SelectedDuplicateUrlBehavior = value.Value;
        }
        finally
        {
            _localizedChoiceSelectionSyncing = false;
        }
    }

    partial void OnSelectedDuplicateUrlBehaviorChanged(DuplicateUrlBehavior value)
    {
        if (!_localizedChoiceSelectionSyncing)
        {
            SelectChoice(DuplicateUrlBehaviorChoices, value, choice => SelectedDuplicateUrlBehaviorChoice = choice);
        }
    }

    partial void OnSelectedProxyModeChoiceChanged(LocalizedChoice<ProxyMode>? value)
    {
        if (value is null || _localizedChoiceSelectionSyncing)
        {
            return;
        }

        _localizedChoiceSelectionSyncing = true;
        try
        {
            SelectedProxyMode = value.Value;
        }
        finally
        {
            _localizedChoiceSelectionSyncing = false;
        }
    }

    partial void OnSelectedProxyModeChanged(ProxyMode value)
    {
        if (!_localizedChoiceSelectionSyncing)
        {
            SelectChoice(ProxyModeChoices, value, choice => SelectedProxyModeChoice = choice);
        }
    }

    partial void OnSelectedProxyAuthenticationModeChoiceChanged(LocalizedChoice<ProxyAuthenticationMode>? value)
    {
        if (value is null || _localizedChoiceSelectionSyncing)
        {
            return;
        }

        _localizedChoiceSelectionSyncing = true;
        try
        {
            SelectedProxyAuthenticationMode = value.Value;
        }
        finally
        {
            _localizedChoiceSelectionSyncing = false;
        }
    }

    partial void OnSelectedProxyAuthenticationModeChanged(ProxyAuthenticationMode value)
    {
        if (!_localizedChoiceSelectionSyncing)
        {
            SelectChoice(ProxyAuthenticationModeChoices, value, choice => SelectedProxyAuthenticationModeChoice = choice);
        }
    }

    partial void OnSelectedAria2ConnectionModeChoiceChanged(LocalizedChoice<Aria2ConnectionMode>? value)
    {
        if (value is null || _localizedChoiceSelectionSyncing)
        {
            return;
        }

        _localizedChoiceSelectionSyncing = true;
        try
        {
            SelectedAria2ConnectionMode = value.Value;
        }
        finally
        {
            _localizedChoiceSelectionSyncing = false;
        }
    }

    partial void OnSelectedUpdateChannelChoiceChanged(LocalizedChoice<UpdateChannel>? value)
    {
        if (value is null || _localizedChoiceSelectionSyncing)
        {
            return;
        }

        _localizedChoiceSelectionSyncing = true;
        try
        {
            SelectedUpdateChannel = value.Value;
        }
        finally
        {
            _localizedChoiceSelectionSyncing = false;
        }
    }

    partial void OnSelectedUpdateChannelChanged(UpdateChannel value)
    {
        if (!_localizedChoiceSelectionSyncing)
        {
            SelectChoice(UpdateChannelChoices, value, choice => SelectedUpdateChannelChoice = choice);
        }
    }

    private void RefreshMiniWindowActionState()
    {
        OnPropertyChanged(nameof(CanPauseSelectedTransfer));
        OnPropertyChanged(nameof(CanResumeSelectedTransfer));
        OnPropertyChanged(nameof(CanCancelSelectedTransfer));
    }
}
