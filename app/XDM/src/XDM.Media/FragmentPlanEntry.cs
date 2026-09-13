namespace XDM.Media;

internal sealed record FragmentPlanEntry(
    string Id,
    Uri Uri,
    long Order,
    string RelativePath,
    string Identity,
    long? ByteRangeOffset = null,
    long? ByteRangeLength = null,
    bool IsInitialization = false,
    bool HasInitialization = false,
    string? InitializationIdentity = null);
