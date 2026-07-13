namespace XDM.DownloadEngine;

public static class TransferArtifactPaths
{
    public static string GetPartialPath(string destinationPath)
        => $"{destinationPath}.xdm.part";

    public static string GetLegacyPartialPath(string destinationPath)
        => $"{destinationPath}.part";

    public static string GetCheckpointPath(string destinationPath)
        => $"{destinationPath}.xdm.resume.json";

    public static string GetFinalizationMarkerPath(string destinationPath)
        => $"{destinationPath}.xdm.finalizing";

    public static string GetLegacyFinalizationMarkerPath(string destinationPath)
        => $"{destinationPath}.finalizing";

    public static string GetFinalizationStagingPath(string destinationPath)
        => $"{destinationPath}.xdm.promoting";

    public static string GetChecksumStatePath(string destinationPath)
        => $"{destinationPath}.xdm.checksums.json";

    public static string GetRepairManifestPath(string localPath)
        => $"{localPath}.xdm.repair.json";

    public static string GetCorruptBackupPath(string destinationPath, DateTimeOffset timestamp)
        => $"{destinationPath}.corrupt-{timestamp:yyyyMMddHHmmss}";

    public static string GetStalePartialPath(string destinationPath, DateTimeOffset timestamp)
        => $"{destinationPath}.stale-{timestamp:yyyyMMddHHmmss}.xdm.part";
}
