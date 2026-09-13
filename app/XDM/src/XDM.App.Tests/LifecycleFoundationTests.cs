using Avalonia;
using XDM.App.Services;

namespace XDM.App.Tests;

public sealed class LifecycleFoundationTests
{
    [Fact]
    public void OffscreenWindowPlacementIsRecoveredToCurrentDisplay()
    {
        WindowPlacementState stale = new(9000, 9000, 1200, 900, true);
        PixelRect display = new(0, 0, 1920, 1080);

        WindowPlacementState restored = MainWindow.NormalizePlacement(stale, [display]);

        Assert.False(restored.IsMaximized);
        Assert.InRange(restored.X, 0, 1920 - 1);
        Assert.InRange(restored.Y, 0, 1080 - 1);
        Assert.InRange(restored.Width, 760, 1920);
        Assert.InRange(restored.Height, 680, 1080);
    }

    [Fact]
    public void VisibleWindowPlacementIsPreserved()
    {
        WindowPlacementState current = new(100, 120, 1000, 760, false);
        WindowPlacementState restored = MainWindow.NormalizePlacement(
            current,
            [new PixelRect(0, 0, 1920, 1080)]);

        Assert.Equal(current, restored);
    }
}
