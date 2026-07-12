using System.Threading.Channels;

namespace XDM.Media;

public sealed class ConversionQueueService : IConversionQueueService, IDisposable
{
    private readonly object _sync = new();
    private readonly IConversionService _conversionService;
    private readonly Channel<string> _pendingJobs = Channel.CreateUnbounded<string>(new UnboundedChannelOptions
    {
        SingleReader = true,
        SingleWriter = false,
        AllowSynchronousContinuations = false
    });
    private readonly List<MutableConversionJob> _jobs = [];
    private readonly CancellationTokenSource _shutdown = new();
    private readonly Task _workerTask;
    private CancellationTokenSource? _activeCancellation;
    private string? _activeJobId;
    private bool _disposed;

    public ConversionQueueService(IConversionService conversionService)
    {
        _conversionService = conversionService;
        _workerTask = Task.Run(ProcessQueueAsync);
    }

    public ConversionQueueSnapshot Current
    {
        get
        {
            lock (_sync)
            {
                return CreateSnapshot();
            }
        }
    }

    public event EventHandler<ConversionQueueSnapshot>? Changed;

    public string Enqueue(ConversionRequest request)
    {
        ObjectDisposedException.ThrowIf(_disposed, this);
        ArgumentNullException.ThrowIfNull(request);
        ConversionPreset preset = _conversionService.Presets.FirstOrDefault(
            candidate => string.Equals(candidate.Id, request.PresetId, StringComparison.Ordinal))
            ?? throw new ArgumentException($"Unknown conversion preset '{request.PresetId}'.", nameof(request));
        string id = Guid.NewGuid().ToString("N");
        lock (_sync)
        {
            _jobs.Add(new MutableConversionJob(
                id,
                request,
                preset.Name,
                DateTimeOffset.UtcNow));
        }

        if (!_pendingJobs.Writer.TryWrite(id))
        {
            lock (_sync)
            {
                MutableConversionJob job = GetJob(id);
                job.State = ConversionJobState.Failed;
                job.StatusMessage = "The conversion queue is unavailable.";
                job.ErrorMessage = "The conversion worker has stopped.";
                job.CompletedAt = DateTimeOffset.UtcNow;
            }
        }

        Publish();
        return id;
    }

    public bool Cancel(string jobId)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(jobId);
        bool changed = false;
        lock (_sync)
        {
            MutableConversionJob? job = _jobs.FirstOrDefault(
                candidate => string.Equals(candidate.Id, jobId, StringComparison.Ordinal));
            if (job is null || IsTerminal(job.State))
            {
                return false;
            }

            if (job.State == ConversionJobState.Queued)
            {
                job.State = ConversionJobState.Cancelled;
                job.StatusMessage = "Cancelled before conversion started.";
                job.CompletedAt = DateTimeOffset.UtcNow;
                changed = true;
            }
            else if (string.Equals(_activeJobId, jobId, StringComparison.Ordinal))
            {
                job.StatusMessage = "Cancellation requested; stopping FFmpeg.";
                _activeCancellation?.Cancel();
                changed = true;
            }
        }

        if (changed)
        {
            Publish();
        }

        return changed;
    }

    public bool Remove(string jobId)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(jobId);
        bool removed;
        lock (_sync)
        {
            int index = _jobs.FindIndex(
                candidate => string.Equals(candidate.Id, jobId, StringComparison.Ordinal)
                    && IsTerminal(candidate.State));
            if (index < 0)
            {
                return false;
            }

            _jobs.RemoveAt(index);
            removed = true;
        }

        if (removed)
        {
            Publish();
        }

        return removed;
    }

    public void Dispose()
    {
        if (_disposed)
        {
            return;
        }

        _disposed = true;
        _pendingJobs.Writer.TryComplete();
        _shutdown.Cancel();
        lock (_sync)
        {
            _activeCancellation?.Cancel();
        }

        if (_workerTask.IsCompleted)
        {
            _workerTask.Dispose();
        }

        _shutdown.Dispose();
        GC.SuppressFinalize(this);
    }

    private async Task ProcessQueueAsync()
    {
        try
        {
            await foreach (string jobId in _pendingJobs.Reader.ReadAllAsync(_shutdown.Token).ConfigureAwait(false))
            {
                MutableConversionJob? job;
                CancellationTokenSource activeCancellation;
                lock (_sync)
                {
                    job = _jobs.FirstOrDefault(
                        candidate => string.Equals(candidate.Id, jobId, StringComparison.Ordinal));
                    if (job is null || job.State != ConversionJobState.Queued)
                    {
                        continue;
                    }

                    activeCancellation = CancellationTokenSource.CreateLinkedTokenSource(_shutdown.Token);
                    _activeCancellation = activeCancellation;
                    _activeJobId = job.Id;
                    job.State = ConversionJobState.Inspecting;
                    job.StatusMessage = "Inspecting source media.";
                    job.StartedAt = DateTimeOffset.UtcNow;
                }

                Publish();
                try
                {
                    IProgress<ConversionProgress> progress = new CallbackProgress<ConversionProgress>(
                        value => UpdateProgress(jobId, value));
                    ConversionResult result = await _conversionService.ConvertAsync(
                        job.Request,
                        progress,
                        activeCancellation.Token).ConfigureAwait(false);
                    lock (_sync)
                    {
                        job.State = ConversionJobState.Completed;
                        job.ProgressFraction = 1;
                        job.StatusMessage = $"Completed: {result.DestinationPath}";
                        job.OutputBytes = result.OutputBytes;
                        job.CompletedAt = DateTimeOffset.UtcNow;
                    }
                }
                catch (OperationCanceledException) when (activeCancellation.IsCancellationRequested)
                {
                    lock (_sync)
                    {
                        job.State = ConversionJobState.Cancelled;
                        job.StatusMessage = "Conversion cancelled. The source file was not modified.";
                        job.CompletedAt = DateTimeOffset.UtcNow;
                    }
                }
#pragma warning disable CA1031 // Queue jobs must capture non-fatal conversion failures and continue with later jobs.
                catch (Exception exception) when (exception is not StackOverflowException and not OutOfMemoryException)
                {
                    lock (_sync)
                    {
                        job.State = ConversionJobState.Failed;
                        job.StatusMessage = "Conversion failed.";
                        job.ErrorMessage = exception.Message;
                        job.CompletedAt = DateTimeOffset.UtcNow;
                    }
                }
#pragma warning restore CA1031
                finally
                {
                    lock (_sync)
                    {
                        _activeCancellation?.Dispose();
                        _activeCancellation = null;
                        _activeJobId = null;
                    }

                    Publish();
                }
            }
        }
        catch (OperationCanceledException) when (_shutdown.IsCancellationRequested)
        {
        }
    }

    private void UpdateProgress(string jobId, ConversionProgress progress)
    {
        lock (_sync)
        {
            MutableConversionJob? job = _jobs.FirstOrDefault(
                candidate => string.Equals(candidate.Id, jobId, StringComparison.Ordinal));
            if (job is null || IsTerminal(job.State))
            {
                return;
            }

            job.State = progress.State;
            job.StatusMessage = progress.Message;
            job.ProgressFraction = progress.Fraction;
            job.OutputBytes = progress.OutputBytes;
        }

        Publish();
    }

    private void Publish()
    {
        ConversionQueueSnapshot snapshot;
        lock (_sync)
        {
            snapshot = CreateSnapshot();
        }

        Changed?.Invoke(this, snapshot);
    }

    private ConversionQueueSnapshot CreateSnapshot()
        => new(
            _jobs.Select(static job => job.ToSnapshot()).ToArray(),
            _activeJobId);

    private MutableConversionJob GetJob(string id)
        => _jobs.First(job => string.Equals(job.Id, id, StringComparison.Ordinal));

    private static bool IsTerminal(ConversionJobState state)
        => state is ConversionJobState.Completed
            or ConversionJobState.Failed
            or ConversionJobState.Cancelled;

    private sealed class MutableConversionJob(
        string id,
        ConversionRequest request,
        string presetName,
        DateTimeOffset createdAt)
    {
        public string Id { get; } = id;

        public ConversionRequest Request { get; } = request;

        public string PresetName { get; } = presetName;

        public ConversionJobState State { get; set; } = ConversionJobState.Queued;

        public double? ProgressFraction { get; set; }

        public string StatusMessage { get; set; } = "Waiting in conversion queue.";

        public string? ErrorMessage { get; set; }

        public DateTimeOffset CreatedAt { get; } = createdAt;

        public DateTimeOffset? StartedAt { get; set; }

        public DateTimeOffset? CompletedAt { get; set; }

        public long? OutputBytes { get; set; }

        public ConversionJobSnapshot ToSnapshot()
            => new(
                Id,
                Request,
                PresetName,
                State,
                ProgressFraction,
                StatusMessage,
                ErrorMessage,
                CreatedAt,
                StartedAt,
                CompletedAt,
                OutputBytes);
    }

    private sealed class CallbackProgress<T>(Action<T> callback) : IProgress<T>
    {
        public void Report(T value)
            => callback(value);
    }
}
