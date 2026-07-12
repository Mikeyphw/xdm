namespace XDM.Media;

public enum ConversionJobState
{
    Queued,
    Inspecting,
    Converting,
    Finalizing,
    Completed,
    Failed,
    Cancelled
}
