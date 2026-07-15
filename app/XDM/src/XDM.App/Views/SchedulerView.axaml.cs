using Avalonia.Controls;

namespace XDM.App.Views;

public partial class SchedulerView : UserControl
{
    public SchedulerView()
    {
        InitializeComponent();
        SizeChanged += (_, _) => ApplyResponsiveLayout(Bounds.Width);
    }

    internal void ApplyResponsiveLayout(double width)
    {
        bool compact = width > 0 && width < 920;
        SetClass("compact-scheduler", compact);

        SchedulerWorkspace.ColumnDefinitions.Clear();
        SchedulerWorkspace.RowDefinitions.Clear();
        if (compact)
        {
            SchedulerWorkspace.ColumnDefinitions.Add(new ColumnDefinition(GridLength.Star));
            SchedulerWorkspace.RowDefinitions.Add(new RowDefinition(GridLength.Auto));
            SchedulerWorkspace.RowDefinitions.Add(new RowDefinition(GridLength.Auto));
            Grid.SetColumn(ScheduleListPane, 0);
            Grid.SetRow(ScheduleListPane, 0);
            Grid.SetColumn(ScheduleEditorPane, 0);
            Grid.SetRow(ScheduleEditorPane, 1);
        }
        else
        {
            SchedulerWorkspace.ColumnDefinitions.Add(new ColumnDefinition(new GridLength(320)));
            SchedulerWorkspace.ColumnDefinitions.Add(new ColumnDefinition(GridLength.Star));
            SchedulerWorkspace.RowDefinitions.Add(new RowDefinition(GridLength.Auto));
            Grid.SetColumn(ScheduleListPane, 0);
            Grid.SetRow(ScheduleListPane, 0);
            Grid.SetColumn(ScheduleEditorPane, 1);
            Grid.SetRow(ScheduleEditorPane, 0);
        }

        ScheduleTimeQueueGrid.ColumnDefinitions.Clear();
        ScheduleTimeQueueGrid.RowDefinitions.Clear();
        int columns = compact ? 1 : 3;
        for (int index = 0; index < columns; index++)
        {
            ScheduleTimeQueueGrid.ColumnDefinitions.Add(new ColumnDefinition(GridLength.Star));
        }
        for (int index = 0; index < (compact ? 3 : 1); index++)
        {
            ScheduleTimeQueueGrid.RowDefinitions.Add(new RowDefinition(GridLength.Auto));
        }

        Control[] fields = [ScheduleStartField, ScheduleEndField, ScheduleQueueField];
        for (int index = 0; index < fields.Length; index++)
        {
            Grid.SetColumn(fields[index], index % columns);
            Grid.SetRow(fields[index], index / columns);
        }
    }

    private void SetClass(string className, bool enabled)
    {
        if (enabled)
        {
            if (!Classes.Contains(className))
            {
                Classes.Add(className);
            }
            return;
        }

        Classes.Remove(className);
    }
}
