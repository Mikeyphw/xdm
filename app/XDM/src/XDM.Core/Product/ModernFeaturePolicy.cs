namespace XDM.Core.Product;

public enum UpdateDeliveryMode
{
    InApplicationVerifiedPackage
}

public static class ModernFeaturePolicy
{
    public static Uri ReleasePage { get; } = new("https://github.com/Mikeyphw/xdm/releases");

    public static Uri UpdateManifest { get; } = new(
        "https://github.com/Mikeyphw/xdm/releases/latest/download/xdm-update.json");

    public static UpdateDeliveryMode UpdateDelivery => UpdateDeliveryMode.InApplicationVerifiedPackage;

    public static bool IsSupportedDownloadUri(Uri? uri)
        => uri is { IsAbsoluteUri: true }
            && uri.Scheme is "http" or "https" or "ftp" or "ftps";

    public static string GetUnsupportedDownloadMessage(Uri? uri)
    {
        string scheme = uri?.IsAbsoluteUri == true ? uri.Scheme : "relative";
        return $"Absolute HTTP, HTTPS, FTP, and FTPS download URLs are supported; '{scheme}' is not.";
    }
}
