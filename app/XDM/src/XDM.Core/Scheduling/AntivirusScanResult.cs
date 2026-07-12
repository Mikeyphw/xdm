namespace XDM.Core.Scheduling;

public sealed record AntivirusScanResult(
    string FilePath,
    bool Succeeded,
    int? ExitCode,
    string Message);
