namespace XDM.BrowserMedia.Tests;

internal static class MediaFixture
{
    public static string Read(string name)
        => File.ReadAllText(Path.Combine(AppContext.BaseDirectory, "Fixtures", "Media", name));
}
