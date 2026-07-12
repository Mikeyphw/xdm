namespace XDM.Media;

internal sealed record ConversionProcessResult(
    int ExitCode,
    string StandardError,
    TimeSpan Elapsed);
