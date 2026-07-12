namespace XDM.Media;

internal interface IConversionProcessRunner
{
    Task<ConversionProcessResult> RunAsync(
        string executablePath,
        IReadOnlyList<string> arguments,
        TimeSpan? expectedDuration,
        IProgress<ConversionProgress>? progress,
        CancellationToken cancellationToken);
}
