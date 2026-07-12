namespace XDM.Media;

public interface IConversionQueueService
{
    ConversionQueueSnapshot Current { get; }

    event EventHandler<ConversionQueueSnapshot>? Changed;

    string Enqueue(ConversionRequest request);

    bool Cancel(string jobId);

    bool Remove(string jobId);
}
