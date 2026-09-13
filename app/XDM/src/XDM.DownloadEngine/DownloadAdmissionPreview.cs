namespace XDM.DownloadEngine;

public sealed record DownloadAdmissionPreview(
    Uri Source,
    string FileName,
    string DestinationPath,
    bool HasConflict,
    bool AutoRenamed);
