using CommunityToolkit.Mvvm.ComponentModel;
using XDM.Core.Scheduling;
using XDM.Core.Settings;

namespace XDM.App.ViewModels;

public partial class ScheduleEditorViewModel : ObservableObject
{
    private static readonly string[] LineSeparators = ["\r\n", "\n"];
    public ScheduleEditorViewModel(
        QueueScheduleDefinition definition,
        IReadOnlyList<DownloadQueueDefinition> queues)
    {
        ArgumentNullException.ThrowIfNull(definition);
        ArgumentNullException.ThrowIfNull(queues);
        Id = definition.Id;
        name = definition.Name;
        enabled = definition.Enabled;
        queue = queues.FirstOrDefault(item => string.Equals(item.Id, definition.QueueId, StringComparison.Ordinal))
            ?? (queues.Count > 0 ? queues[0] : null);
        startTime = definition.StartTime.ToString("HH:mm", System.Globalization.CultureInfo.InvariantCulture);
        endTime = definition.EndTime.ToString("HH:mm", System.Globalization.CultureInfo.InvariantCulture);
        mondayEnabled = definition.Days.HasFlag(WeekDays.Monday);
        tuesdayEnabled = definition.Days.HasFlag(WeekDays.Tuesday);
        wednesdayEnabled = definition.Days.HasFlag(WeekDays.Wednesday);
        thursdayEnabled = definition.Days.HasFlag(WeekDays.Thursday);
        fridayEnabled = definition.Days.HasFlag(WeekDays.Friday);
        saturdayEnabled = definition.Days.HasFlag(WeekDays.Saturday);
        sundayEnabled = definition.Days.HasFlag(WeekDays.Sunday);
        missedRunPolicy = definition.MissedRunPolicy;
        completionAction = definition.CompletionAction.Kind;
        countdownSeconds = definition.CompletionAction.CountdownSeconds
            .ToString(System.Globalization.CultureInfo.InvariantCulture);
        commandPath = definition.CompletionAction.ExecutablePath ?? string.Empty;
        commandArguments = string.Join(Environment.NewLine, definition.CompletionAction.Arguments ?? []);
    }

    public string Id { get; }

    public IReadOnlyList<MissedRunPolicy> MissedRunOptions { get; } = Enum.GetValues<MissedRunPolicy>();

    public IReadOnlyList<ScheduleCompletionActionKind> CompletionActionOptions { get; } =
        Enum.GetValues<ScheduleCompletionActionKind>();

    [ObservableProperty]
    private string name;

    [ObservableProperty]
    private bool enabled;

    [ObservableProperty]
    private DownloadQueueDefinition? queue;

    [ObservableProperty]
    private string startTime;

    [ObservableProperty]
    private string endTime;

    [ObservableProperty]
    private bool mondayEnabled;

    [ObservableProperty]
    private bool tuesdayEnabled;

    [ObservableProperty]
    private bool wednesdayEnabled;

    [ObservableProperty]
    private bool thursdayEnabled;

    [ObservableProperty]
    private bool fridayEnabled;

    [ObservableProperty]
    private bool saturdayEnabled;

    [ObservableProperty]
    private bool sundayEnabled;

    [ObservableProperty]
    private MissedRunPolicy missedRunPolicy;

    [ObservableProperty]
    private ScheduleCompletionActionKind completionAction;

    [ObservableProperty]
    private string countdownSeconds;

    [ObservableProperty]
    private string commandPath;

    [ObservableProperty]
    private string commandArguments;

    public QueueScheduleDefinition ToDefinition()
    {
        TimeOnly parsedStart = TimeOnly.TryParseExact(
            StartTime,
            "HH:mm",
            System.Globalization.CultureInfo.InvariantCulture,
            System.Globalization.DateTimeStyles.None,
            out TimeOnly start)
                ? start
                : new TimeOnly(0, 0);
        TimeOnly parsedEnd = TimeOnly.TryParseExact(
            EndTime,
            "HH:mm",
            System.Globalization.CultureInfo.InvariantCulture,
            System.Globalization.DateTimeStyles.None,
            out TimeOnly end)
                ? end
                : new TimeOnly(23, 59);
        int countdown = int.TryParse(
            CountdownSeconds,
            System.Globalization.NumberStyles.Integer,
            System.Globalization.CultureInfo.InvariantCulture,
            out int parsedCountdown)
                ? parsedCountdown
                : 30;
        string[] arguments = CommandArguments
            .Split(LineSeparators, StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries);
        ScheduleCompletionAction action = new(
            CompletionAction,
            countdown,
            string.IsNullOrWhiteSpace(CommandPath) ? null : CommandPath.Trim(),
            arguments);
        WeekDays selectedDays = WeekDays.None;
        selectedDays |= MondayEnabled ? WeekDays.Monday : WeekDays.None;
        selectedDays |= TuesdayEnabled ? WeekDays.Tuesday : WeekDays.None;
        selectedDays |= WednesdayEnabled ? WeekDays.Wednesday : WeekDays.None;
        selectedDays |= ThursdayEnabled ? WeekDays.Thursday : WeekDays.None;
        selectedDays |= FridayEnabled ? WeekDays.Friday : WeekDays.None;
        selectedDays |= SaturdayEnabled ? WeekDays.Saturday : WeekDays.None;
        selectedDays |= SundayEnabled ? WeekDays.Sunday : WeekDays.None;
        return new QueueScheduleDefinition(
            Id,
            string.IsNullOrWhiteSpace(Name) ? "Schedule" : Name.Trim(),
            Enabled,
            Queue?.Id ?? "default",
            parsedStart,
            parsedEnd,
            selectedDays,
            MissedRunPolicy,
            action).Normalize(Queue?.Id ?? "default");
    }
}
