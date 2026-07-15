using Avalonia.Controls;

namespace XDM.App.Views;

public partial class BrowserIntegrationView : UserControl
{
    public BrowserIntegrationView()
    {
        InitializeComponent();
        SizeChanged += (_, _) => ApplyResponsiveLayout(Bounds.Width);
    }

    internal void ApplyResponsiveLayout(double width)
    {
        bool compact = width > 0 && width < 920;
        SetClass("compact-browser", compact);
        ConfigureGrid(BrowserSummaryGrid, [BrowserCaptureSummaryCard, BrowserExtensionSummaryCard, BrowserProtocolSummaryCard], compact ? 1 : 3);
        ConfigureGrid(BrowserHostPermissionsGrid, [BrowserHostCard, BrowserPermissionsCard], compact ? 1 : 2);
        ConfigureGrid(BrowserActivityGrid, [BrowserLastCaptureCard, BrowserMediaProbeCard], compact ? 1 : 2);
    }

    private static void ConfigureGrid(Grid grid, IReadOnlyList<Control> children, int columns)
    {
        grid.ColumnDefinitions.Clear();
        grid.RowDefinitions.Clear();
        for (int index = 0; index < columns; index++)
        {
            grid.ColumnDefinitions.Add(new ColumnDefinition(GridLength.Star));
        }
        for (int index = 0; index < Math.Ceiling(children.Count / (double)columns); index++)
        {
            grid.RowDefinitions.Add(new RowDefinition(GridLength.Auto));
        }
        for (int index = 0; index < children.Count; index++)
        {
            Grid.SetColumn(children[index], index % columns);
            Grid.SetRow(children[index], index / columns);
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
