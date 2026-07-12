namespace XDM.DownloadEngine;

public sealed record MetalinkFileEntry(
    string FileName,
    long? Size,
    IReadOnlyList<Uri> Sources,
    string? ChecksumAlgorithm,
    string? Checksum);
