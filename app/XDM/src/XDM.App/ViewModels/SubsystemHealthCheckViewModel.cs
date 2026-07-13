using CommunityToolkit.Mvvm.Input;
using XDM.Diagnostics;

namespace XDM.App.ViewModels;

public sealed class SubsystemHealthCheckViewModel
{
    private readonly Func<string, Task> _repair;

    public SubsystemHealthCheckViewModel(
        SubsystemHealthCheckResult result,
        Func<string, Task> repair)
    {
        _repair = repair;
        Id = result.Id;
        Name = result.Name;
        Status = result.Status.ToString();
        Summary = result.Summary;
        Details = result.Details;
        DurationText = result.DurationText;
        RepairActionId = result.RepairActionId;
        RepairLabel = result.RepairLabel ?? "Repair";
        RepairCommand = new AsyncRelayCommand(RepairAsync, () => CanRepair);
    }

    public string Id { get; }

    public string Name { get; }

    public string Status { get; }

    public string Summary { get; }

    public string Details { get; }

    public string DurationText { get; }

    public string? RepairActionId { get; }

    public string RepairLabel { get; }

    public bool CanRepair => !string.IsNullOrWhiteSpace(RepairActionId);

    public IAsyncRelayCommand RepairCommand { get; }

    private Task RepairAsync()
        => RepairActionId is null ? Task.CompletedTask : _repair(RepairActionId);
}
