namespace XDM.Core.Product;

public enum UpdateDeliveryMode
{
    InApplicationVerifiedPackage
}

public static class ModernFeaturePolicy
{
    public static Uri ReleasePage { get; } = new("https://github.com/Mikeyphw/xdm/releases");

    public static Uri UpdateManifest => GetUpdateManifest(UpdateChannel.Stable);

    public static Uri GetUpdateManifest(UpdateChannel channel)
        => channel switch
        {
            UpdateChannel.Stable => new Uri(
                "https://github.com/Mikeyphw/xdm/releases/latest/download/xdm-update-stable.json"),
            UpdateChannel.Beta => new Uri(
                "https://github.com/Mikeyphw/xdm/releases/download/beta/xdm-update-beta.json"),
            UpdateChannel.Nightly => new Uri(
                "https://github.com/Mikeyphw/xdm/releases/download/nightly/xdm-update-nightly.json"),
            _ => throw new ArgumentOutOfRangeException(nameof(channel), channel, "Unsupported update channel.")
        };

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
