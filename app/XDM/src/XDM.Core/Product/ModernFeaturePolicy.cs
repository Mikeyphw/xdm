namespace XDM.Core.Product;

public enum UpdateDeliveryMode
{
    ExternalSignedPackage
}

public static class ModernFeaturePolicy
{
    public static Uri ReleasePage { get; } = new("https://github.com/Mikeyphw/xdm/releases");

    public static UpdateDeliveryMode UpdateDelivery => UpdateDeliveryMode.ExternalSignedPackage;

    public static bool IsSupportedDownloadUri(Uri? uri)
        => uri is { IsAbsoluteUri: true }
            && uri.Scheme is "http" or "https";

    public static string GetUnsupportedDownloadMessage(Uri? uri)
    {
        string scheme = uri?.IsAbsoluteUri == true ? uri.Scheme : "relative";
        return scheme is "ftp" or "ftps"
            ? "FTP and FTPS are intentionally not handled by the modern downloader. Use an HTTPS source or a dedicated file-transfer client."
            : "Only absolute HTTP and HTTPS download URLs are supported.";
    }
}
