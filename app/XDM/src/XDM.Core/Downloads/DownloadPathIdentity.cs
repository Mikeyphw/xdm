namespace XDM.Core.Downloads;

public static class DownloadPathIdentity
{
    public static StringComparison Comparison => OperatingSystem.IsWindows()
        ? StringComparison.OrdinalIgnoreCase
        : StringComparison.Ordinal;

    public static StringComparer Comparer => OperatingSystem.IsWindows()
        ? StringComparer.OrdinalIgnoreCase
        : StringComparer.Ordinal;

    public static bool Equals(string left, string right)
        => string.Equals(Path.GetFullPath(left), Path.GetFullPath(right), Comparison);
}
