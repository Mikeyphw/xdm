namespace XDM.DownloadEngine;

public enum FinalizationStage
{
    Prepared = 0,
    PromotionStarted = 1,
    CopyingToDestination = 2,
    DestinationReady = 3,
    DestinationCommitted = 4,
    MetadataCommitted = 5
}
