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
    public void AllShellLocalizationBindingsResolveToEnglishCatalogKeys()
    {
        string catalogPath = Path.Combine(AppContext.BaseDirectory, "Fixtures", "strings.en.json");
        using FileStream stream = File.OpenRead(catalogPath);
        Dictionary<string, string> resources = JsonSerializer.Deserialize<Dictionary<string, string>>(stream)
            ?? throw new InvalidDataException("Localization fixture is invalid.");

        string fixturesRoot = Path.Combine(AppContext.BaseDirectory, "Fixtures");
        string[] fixtureFiles = Directory.GetFiles(fixturesRoot, "*.axaml", SearchOption.AllDirectories);
        foreach (string fixture in fixtureFiles)
        {
            string text = File.ReadAllText(fixture);
            foreach (string key in ExtractLocalizationKeys(text))
            {
                Assert.True(resources.ContainsKey(key), $"Localization key '{key}' referenced by {Path.GetFileName(fixture)} is missing from strings.en.json.");
            }
        }
    }

    private static IEnumerable<string> ExtractLocalizationKeys(string text)
    {
        const string marker = "Localization[";
        int index = 0;
        while (index < text.Length)
        {
            int start = text.IndexOf(marker, index, StringComparison.Ordinal);
            if (start < 0)
            {
                yield break;
            }

            start += marker.Length;
            int end = text.IndexOf(']', start);
            if (end < 0)
            {
                yield break;
            }

            yield return text[start..end];
            index = end + 1;
        }
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
