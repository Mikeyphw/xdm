namespace XDM.Media;

public sealed record ExternalToolHealth(
    string Tool,
    bool IsAvailable,
    string? ExecutablePath,
    string? Version,
    string Message);
