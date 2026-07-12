namespace XDM.Media;

internal sealed record ExternalMediaFormatData(
    string DirectUrl,
    string? Protocol,
    string FormatId,
    IReadOnlyList<ExternalMediaFragment> Fragments);

internal sealed record ExternalMediaFragment(string Id, Uri Uri);
