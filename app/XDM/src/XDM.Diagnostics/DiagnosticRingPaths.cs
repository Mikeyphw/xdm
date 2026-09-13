namespace XDM.Diagnostics;

internal static class DiagnosticRingPaths
{
    public static string EventRingPath()
        => Path.Combine(BaseDirectory(), "events.ring.json");

    public static string TransferRingPath()
        => Path.Combine(BaseDirectory(), "transfer.ring.json");

    private static string BaseDirectory()
    {
        string local = Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData);
        if (string.IsNullOrWhiteSpace(local))
        {
            local = Path.Combine(Path.GetTempPath(), "XDM.Modern");
        }

        return Path.Combine(local, "XDM.Modern", "diagnostics");
    }
}
