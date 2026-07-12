using System.Text.Json;
using System.Xml.Linq;

namespace XDM.Core.Tests;

public sealed class AccessibilitySurfaceTests
{
    [Fact]
    public void MainWindowDeclaresKeyboardAutomationLiveRegionsAndScaling()
    {
        string path = Path.Combine(AppContext.BaseDirectory, "Fixtures", "MainWindow.axaml");
        XDocument document = XDocument.Load(path, LoadOptions.None);
        XElement root = Assert.IsType<XElement>(document.Root);
        XElement[] elements = root.DescendantsAndSelf().ToArray();

        Assert.Equal("MainWindow_KeyDown", root.Attribute("KeyDown")?.Value);
        Assert.Contains(elements, static element => element.Name.LocalName == "LayoutTransformControl");
        Assert.Contains(elements, static element =>
            string.Equals(element.Attribute("AutomationProperties.LiveSetting")?.Value, "Polite", StringComparison.Ordinal));
        Assert.True(elements.Count(static element => element.Attribute("AutomationProperties.Name") is not null) >= 4);
        Assert.Contains(elements, static element =>
            element.Name.LocalName == "Style"
            && string.Equals(element.Attribute("Selector")?.Value, "Window.high-contrast", StringComparison.Ordinal));
        Assert.Contains(elements, static element =>
            element.Name.LocalName == "SolidColorBrush"
            && string.Equals(element.Attribute(XName.Get("Key", "http://schemas.microsoft.com/winfx/2006/xaml"))?.Value, "XdmWindowBackground", StringComparison.Ordinal));
        Assert.Equal("{DynamicResource XdmWindowBackground}", root.Attribute("Background")?.Value);
    }

    [Fact]
    public void EnglishCatalogUsesStableUniqueKeysForTheModernSurface()
    {
        string path = Path.Combine(AppContext.BaseDirectory, "Fixtures", "strings.en.json");
        using FileStream stream = File.OpenRead(path);
        Dictionary<string, string> resources = JsonSerializer.Deserialize<Dictionary<string, string>>(stream)
            ?? throw new InvalidDataException("Localization fixture is invalid.");

        Assert.True(resources.Count >= 200);
        Assert.All(resources, static pair =>
        {
            Assert.DoesNotContain(' ', pair.Key);
            Assert.False(string.IsNullOrWhiteSpace(pair.Value));
        });
    }
}
