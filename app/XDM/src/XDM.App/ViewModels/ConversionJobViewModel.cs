using CommunityToolkit.Mvvm.ComponentModel;
using XDM.Media;

namespace XDM.App.ViewModels;

public sealed partial class ConversionJobViewModel : ObservableObject
{
    public ConversionJobViewModel(ConversionJobSnapshot snapshot)
    {
        Id = snapshot.Id;
        SourcePath = snapshot.Request.SourcePath;
        DestinationPath = snapshot.Request.DestinationPath;
        PresetName = snapshot.PresetName;
        Apply(snapshot);
    }

    public string Id { get; }

    public string SourcePath { get; }

    public string DestinationPath { get; }

    public string PresetName { get; }

    [ObservableProperty]
    private string state = string.Empty;

    [ObservableProperty]
    private string statusMessage = string.Empty;

    [ObservableProperty]
    private string? errorMessage;

    [ObservableProperty]
    private double progressPercent;

    [ObservableProperty]
    private bool isIndeterminate;

    [ObservableProperty]
    private bool canCancel;

    [ObservableProperty]
    private bool canRemove;

    [ObservableProperty]
    private string outputSize = "—";

    public void Apply(ConversionJobSnapshot snapshot)
    {
        State = snapshot.State.ToString();
        StatusMessage = snapshot.StatusMessage;
        ErrorMessage = snapshot.ErrorMessage;
        ProgressPercent = (snapshot.ProgressFraction ?? 0) * 100;
        IsIndeterminate = snapshot.ProgressFraction is null
            && snapshot.State is ConversionJobState.Inspecting or ConversionJobState.Converting or ConversionJobState.Finalizing;
        CanCancel = snapshot.State is ConversionJobState.Queued
            or ConversionJobState.Inspecting
            or ConversionJobState.Converting
            or ConversionJobState.Finalizing;
        CanRemove = snapshot.State is ConversionJobState.Completed
            or ConversionJobState.Failed
            or ConversionJobState.Cancelled;
        OutputSize = snapshot.OutputBytes is long bytes ? FormatBytes(bytes) : "—";
    }

    private static string FormatBytes(long bytes)
    {
        string[] units = ["B", "KB", "MB", "GB", "TB"];
        double value = Math.Max(0, bytes);
        int unit = 0;
        while (value >= 1024 && unit < units.Length - 1)
        {
            value /= 1024;
            unit++;
        }

        return $"{value:0.#} {units[unit]}";
    }
}
