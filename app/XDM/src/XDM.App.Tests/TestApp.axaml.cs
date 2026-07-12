using Avalonia;
using Avalonia.Markup.Xaml;

namespace XDM.App.Tests;

public sealed partial class TestApp : Application
{
    public override void Initialize()
    {
        AvaloniaXamlLoader.Load(this);
    }
}
