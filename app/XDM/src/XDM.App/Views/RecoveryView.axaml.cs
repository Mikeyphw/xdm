using Avalonia.Controls;
using Avalonia.Markup.Xaml;

namespace XDM.App.Views;

public partial class RecoveryView : UserControl
{
    public RecoveryView()
    {
        InitializeComponent();
    }

    private void InitializeComponent()
        => AvaloniaXamlLoader.Load(this);
}
