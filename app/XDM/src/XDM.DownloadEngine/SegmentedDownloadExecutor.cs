using System.Diagnostics;
using System.Net;
using System.Net.Http.Headers;
using XDM.Core.Diagnostics;

namespace XDM.DownloadEngine;

internal sealed class SegmentedDownloadExecutor
{
    private const int BufferSize = 64 * 1024;
    private const long DiskSafetyMarginBytes = 8L * 1024 * 1024;
    private readonly HttpClient _httpClient;
    private readonly IDiskSpaceProvider _diskSpaceProvider;
    private readonly DownloadRetryPolicy _retryPolicy;
    private readonly SegmentedDownloadOptions _options;
    private readonly ITransferDiagnosticSink _diagnostics;

    public SegmentedDownloadExecutor(
        HttpClient httpClient,
        IDiskSpaceProvider diskSpaceProvider,
        DownloadRetryPolicy retryPolicy,
        SegmentedDownloadOptions options,
        ITransferDiagnosticSink? diagnostics = null)
    {
        _httpClient = httpClient;
        _diskSpaceProvider = diskSpaceProvider;
        _retryPolicy = retryPolicy;
        _options = options.Normalize();
        _diagnostics = diagnostics ?? NullTransferDiagnosticSink.Instance;
    }

    public async Task<SegmentedDownloadResult?> TryDownloadAsync(
        SegmentedDownloadContext context,
        Func<long, long, double, ValueTask> progress,
        CancellationToken cancellationToken)
    {
        Record(context, TransferDiagnosticStage.Http, TransferDiagnosticSeverity.Information,
            "XDM-TRANSFER-SEGMENT-PROBE", "Probing range support for segmented transfer.");
        ProbeResult? probe = await ProbeAsync(context, cancellationToken).ConfigureAwait(false);
        if (probe is null
            || probe.TotalBytes < _options.MinimumFileSizeBytes
            || context.ConnectionCount <= 1)
        {
            Record(context, TransferDiagnosticStage.Http, TransferDiagnosticSeverity.Information,
                "XDM-TRANSFER-SEGMENT-BYPASS", "Segmented transfer was not selected after the bounded range probe.");
            return null;
        }

        int connectionCount = Math.Clamp(
            context.ConnectionCount,
            1,
            _options.MaximumConnectionCount);
        SegmentedDownloadPlan plan = SegmentedDownloadPlan.Create(probe.TotalBytes, connectionCount);
        plan.Validate();
        string partialPath = TransferArtifactPaths.GetPartialPath(context.DestinationPath);
        string segmentDirectory = GetSegmentDirectory(context.DestinationPath);
        Directory.CreateDirectory(segmentDirectory);
        string mergePath = $"{partialPath}.merge";
        if (File.Exists(mergePath))
        {
            File.Delete(mergePath);
        }

        long existingBytes = GetExistingBytes(plan, segmentDirectory);
        EnsureDiskCapacity(context.DestinationPath, plan.TotalLength, existingBytes);
        Record(context, TransferDiagnosticStage.Disk, TransferDiagnosticSeverity.Information,
            "XDM-TRANSFER-SEGMENT-DISK", "Destination capacity was checked for segment files and the merge file.",
            new Dictionary<string, string?>(StringComparer.Ordinal)
            {
                ["totalBytes"] = plan.TotalLength.ToString(System.Globalization.CultureInfo.InvariantCulture),
                ["existingSegmentBytes"] = existingBytes.ToString(System.Globalization.CultureInfo.InvariantCulture)
            });
        long completedBytes = existingBytes;
        long progressStartBytes = existingBytes;
        Stopwatch speedWatch = Stopwatch.StartNew();
        SegmentedBandwidthLimiter limiter = new(context.SpeedLimitBytesPerSecond);
        await progress(existingBytes, plan.TotalLength, 0).ConfigureAwait(false);

        ParallelOptions parallelOptions = new()
        {
            MaxDegreeOfParallelism = plan.Segments.Count,
            CancellationToken = cancellationToken
        };
        await Parallel.ForEachAsync(
            plan.Segments,
            parallelOptions,
            async (segment, token) =>
            {
                string segmentPath = GetSegmentPath(segmentDirectory, segment.Index);
                await DownloadSegmentAsync(
                    context,
                    probe,
                    segment,
                    segmentPath,
                    limiter,
                    async bytes =>
                    {
                        long current = Interlocked.Add(ref completedBytes, bytes);
                        double elapsedSeconds = Math.Max(speedWatch.Elapsed.TotalSeconds, 0.001);
                        double speed = (current - progressStartBytes) / elapsedSeconds;
                        await progress(current, plan.TotalLength, speed).ConfigureAwait(false);
                    },
                    token).ConfigureAwait(false);
            }).ConfigureAwait(false);

        await MergeAsync(plan, segmentDirectory, mergePath, cancellationToken).ConfigureAwait(false);
        File.Move(mergePath, partialPath, overwrite: true);
        Directory.Delete(segmentDirectory, recursive: true);
        await progress(plan.TotalLength, plan.TotalLength, 0).ConfigureAwait(false);
        Record(context, TransferDiagnosticStage.Resume, TransferDiagnosticSeverity.Information,
            "XDM-TRANSFER-SEGMENT-VALIDATED", "All segment ranges were validated and merged.",
            new Dictionary<string, string?>(StringComparer.Ordinal)
            {
                ["segments"] = plan.Segments.Count.ToString(System.Globalization.CultureInfo.InvariantCulture),
                ["totalBytes"] = plan.TotalLength.ToString(System.Globalization.CultureInfo.InvariantCulture)
            });
        return new SegmentedDownloadResult(plan.TotalLength, probe.EntityTag, probe.LastModified);
    }

    public static string GetSegmentDirectory(string destinationPath)
        => $"{destinationPath}.segments";

    private async Task<ProbeResult?> ProbeAsync(
        SegmentedDownloadContext context,
        CancellationToken cancellationToken)
    {
        using HttpRequestMessage request = new(HttpMethod.Get, context.Source);
        HttpRequestMetadata.Apply(
            request,
            context.Headers,
            context.Username,
            context.Password,
            context.Cookie,
            context.Referer,
            context.UserAgent);
        request.Headers.Range = new RangeHeaderValue(0, 0);
        using HttpResponseMessage response = await _httpClient.SendAsync(
            request,
            HttpCompletionOption.ResponseHeadersRead,
            cancellationToken).ConfigureAwait(false);

        Record(context, TransferDiagnosticStage.Http,
            response.StatusCode == HttpStatusCode.PartialContent
                ? TransferDiagnosticSeverity.Information
                : TransferDiagnosticSeverity.Warning,
            "XDM-TRANSFER-SEGMENT-PROBE-RESPONSE",
            $"Segment probe returned HTTP {(int)response.StatusCode} {response.ReasonPhrase}.",
            CreateResponseDiagnosticContext(response));
        if (response.StatusCode != HttpStatusCode.PartialContent)
        {
            return null;
        }

        ContentRangeHeaderValue? range = response.Content.Headers.ContentRange;
        if (range?.From != 0 || range.To != 0 || range.Length is not long totalBytes || totalBytes <= 0)
        {
            return null;
        }

        return new ProbeResult(
            totalBytes,
            response.Headers.ETag?.ToString(),
            response.Content.Headers.LastModified);
    }

    private async Task DownloadSegmentAsync(
        SegmentedDownloadContext context,
        ProbeResult probe,
        DownloadSegment segment,
        string segmentPath,
        SegmentedBandwidthLimiter limiter,
        Func<int, ValueTask> reportBytes,
        CancellationToken cancellationToken)
    {
        long existingLength = File.Exists(segmentPath) ? new FileInfo(segmentPath).Length : 0;
        if (existingLength > segment.Length)
        {
            File.Delete(segmentPath);
            existingLength = 0;
        }

        Record(context, TransferDiagnosticStage.Http, TransferDiagnosticSeverity.Information,
            "XDM-TRANSFER-SEGMENT-STATE", $"Segment {segment.Index} is ready for transfer.",
            CreateSegmentContext(segment, existingLength));
        if (existingLength == segment.Length)
        {
            Record(context, TransferDiagnosticStage.Http, TransferDiagnosticSeverity.Information,
                "XDM-TRANSFER-SEGMENT-COMPLETED", $"Segment {segment.Index} was already complete.",
                CreateSegmentContext(segment, existingLength));
            return;
        }

        for (int attempt = 1; attempt <= _retryPolicy.MaximumAttempts; attempt++)
        {
            if (existingLength == segment.Length)
            {
                return;
            }

            try
            {
                await DownloadSegmentAttemptAsync(
                    context,
                    probe,
                    segment,
                    segmentPath,
                    existingLength,
                    limiter,
                    reportBytes,
                    cancellationToken).ConfigureAwait(false);
                Record(context, TransferDiagnosticStage.Http, TransferDiagnosticSeverity.Information,
                    "XDM-TRANSFER-SEGMENT-COMPLETED", $"Segment {segment.Index} completed and was length-validated.",
                    CreateSegmentContext(segment, segment.Length));
                return;
            }
            catch (OperationCanceledException) when (cancellationToken.IsCancellationRequested)
            {
                throw;
            }
            catch (Exception exception) when (
                DownloadRetryPolicy.IsTransient(exception)
                && attempt < _retryPolicy.MaximumAttempts)
            {
                existingLength = File.Exists(segmentPath) ? new FileInfo(segmentPath).Length : 0;
                TimeSpan delay = _retryPolicy.GetDelay(attempt);
                Record(context, TransferDiagnosticStage.Retry, TransferDiagnosticSeverity.Warning,
                    "XDM-TRANSFER-SEGMENT-RETRY", $"Segment {segment.Index} failed transiently and will be retried.",
                    new Dictionary<string, string?>(StringComparer.Ordinal)
                    {
                        ["attempt"] = attempt.ToString(System.Globalization.CultureInfo.InvariantCulture),
                        ["delayMilliseconds"] = delay.TotalMilliseconds.ToString("0", System.Globalization.CultureInfo.InvariantCulture),
                        ["exception"] = exception.GetType().Name,
                        ["segmentIndex"] = segment.Index.ToString(System.Globalization.CultureInfo.InvariantCulture),
                        ["segmentStart"] = segment.Start.ToString(System.Globalization.CultureInfo.InvariantCulture),
                        ["segmentEnd"] = segment.End.ToString(System.Globalization.CultureInfo.InvariantCulture)
                    });
                await Task.Delay(delay, cancellationToken).ConfigureAwait(false);
            }
        }
    }

    private async Task DownloadSegmentAttemptAsync(
        SegmentedDownloadContext context,
        ProbeResult probe,
        DownloadSegment segment,
        string segmentPath,
        long existingLength,
        SegmentedBandwidthLimiter limiter,
        Func<int, ValueTask> reportBytes,
        CancellationToken cancellationToken)
    {
        long requestStart = checked(segment.Start + existingLength);
        using HttpRequestMessage request = new(HttpMethod.Get, context.Source);
        HttpRequestMetadata.Apply(
            request,
            context.Headers,
            context.Username,
            context.Password,
            context.Cookie,
            context.Referer,
            context.UserAgent);
        request.Headers.Range = new RangeHeaderValue(requestStart, segment.End);
        ApplyIfRange(request, probe);

        using HttpResponseMessage response = await _httpClient.SendAsync(
            request,
            HttpCompletionOption.ResponseHeadersRead,
            cancellationToken).ConfigureAwait(false);
        if (response.StatusCode != HttpStatusCode.PartialContent)
        {
            throw new DownloadIntegrityException(
                $"The server stopped honoring byte ranges for segment {segment.Index}.");
        }

        ValidateResponse(response, probe, requestStart, segment.End);
        await using Stream source = await response.Content.ReadAsStreamAsync(cancellationToken).ConfigureAwait(false);
        await using FileStream destination = new(
            segmentPath,
            existingLength == 0 ? FileMode.Create : FileMode.Append,
            FileAccess.Write,
            FileShare.Read,
            BufferSize,
            FileOptions.Asynchronous | FileOptions.SequentialScan);
        byte[] buffer = new byte[BufferSize];
        while (true)
        {
            int read = await source.ReadAsync(buffer, cancellationToken).ConfigureAwait(false);
            if (read == 0)
            {
                break;
            }

            await destination.WriteAsync(buffer.AsMemory(0, read), cancellationToken).ConfigureAwait(false);
            await limiter.ThrottleAsync(read, cancellationToken).ConfigureAwait(false);
            await reportBytes(read).ConfigureAwait(false);
        }

        await destination.FlushAsync(cancellationToken).ConfigureAwait(false);
        destination.Flush(flushToDisk: true);
        long finalLength = new FileInfo(segmentPath).Length;
        if (finalLength != segment.Length)
        {
            throw new EndOfStreamException(
                $"Segment {segment.Index} ended at {finalLength} bytes; {segment.Length} bytes were expected.");
        }
    }

    private static async Task MergeAsync(
        SegmentedDownloadPlan plan,
        string segmentDirectory,
        string mergePath,
        CancellationToken cancellationToken)
    {
        await using FileStream destination = new(
            mergePath,
            FileMode.Create,
            FileAccess.Write,
            FileShare.None,
            BufferSize,
            FileOptions.Asynchronous | FileOptions.SequentialScan);
        foreach (DownloadSegment segment in plan.Segments)
        {
            string path = GetSegmentPath(segmentDirectory, segment.Index);
            FileInfo info = new(path);
            if (!info.Exists || info.Length != segment.Length)
            {
                throw new DownloadIntegrityException($"Segment {segment.Index} is missing or incomplete.");
            }

            await using FileStream source = new(
                path,
                FileMode.Open,
                FileAccess.Read,
                FileShare.Read,
                BufferSize,
                FileOptions.Asynchronous | FileOptions.SequentialScan);
            await source.CopyToAsync(destination, BufferSize, cancellationToken).ConfigureAwait(false);
        }

        await destination.FlushAsync(cancellationToken).ConfigureAwait(false);
        destination.Flush(flushToDisk: true);
        if (destination.Length != plan.TotalLength)
        {
            throw new DownloadIntegrityException("The merged segmented download has an invalid length.");
        }
    }

    private void EnsureDiskCapacity(string destinationPath, long totalBytes, long existingSegmentBytes)
    {
        long remainingSegmentBytes = Math.Max(0, totalBytes - existingSegmentBytes);
        long mergeBytes = totalBytes;
        long safetyMargin = Math.Min(DiskSafetyMarginBytes, Math.Max(64L * 1024, totalBytes / 100));
        long requiredBytes = checked(remainingSegmentBytes + mergeBytes + safetyMargin);
        long? availableBytes = _diskSpaceProvider.GetAvailableBytes(destinationPath);
        if (availableBytes is long available && available < requiredBytes)
        {
            throw new InsufficientDiskSpaceException(requiredBytes, available, destinationPath);
        }
    }

    private static long GetExistingBytes(SegmentedDownloadPlan plan, string segmentDirectory)
    {
        long total = 0;
        foreach (DownloadSegment segment in plan.Segments)
        {
            string path = GetSegmentPath(segmentDirectory, segment.Index);
            if (!File.Exists(path))
            {
                continue;
            }

            long length = new FileInfo(path).Length;
            if (length > segment.Length)
            {
                File.Delete(path);
                continue;
            }

            total = checked(total + length);
        }

        return total;
    }

    private static string GetSegmentPath(string segmentDirectory, int index)
        => Path.Combine(segmentDirectory, $"{index:D4}.part");

    private static void ApplyIfRange(HttpRequestMessage request, ProbeResult probe)
    {
        if (!string.IsNullOrWhiteSpace(probe.EntityTag)
            && EntityTagHeaderValue.TryParse(probe.EntityTag, out EntityTagHeaderValue? entityTag)
            && !entityTag.IsWeak)
        {
            request.Headers.IfRange = new RangeConditionHeaderValue(entityTag);
        }
        else if (probe.LastModified is DateTimeOffset lastModified)
        {
            request.Headers.IfRange = new RangeConditionHeaderValue(lastModified);
        }
    }

    private static void ValidateResponse(
        HttpResponseMessage response,
        ProbeResult probe,
        long expectedStart,
        long expectedEnd)
    {
        ContentRangeHeaderValue? range = response.Content.Headers.ContentRange;
        if (range?.From != expectedStart || range.To != expectedEnd || range.Length != probe.TotalBytes)
        {
            throw new DownloadIntegrityException("A segment response returned an invalid Content-Range.");
        }

        string? entityTag = response.Headers.ETag?.ToString();
        if (!string.IsNullOrWhiteSpace(probe.EntityTag)
            && !string.IsNullOrWhiteSpace(entityTag)
            && !string.Equals(probe.EntityTag, entityTag, StringComparison.Ordinal))
        {
            throw new DownloadIntegrityException("The remote file entity tag changed during segmented transfer.");
        }

        DateTimeOffset? lastModified = response.Content.Headers.LastModified;
        if (probe.LastModified is DateTimeOffset expected
            && lastModified is DateTimeOffset actual
            && expected != actual)
        {
            throw new DownloadIntegrityException("The remote file modification date changed during segmented transfer.");
        }
    }


    private static Dictionary<string, string?> CreateResponseDiagnosticContext(HttpResponseMessage response)
    {
        Dictionary<string, string?> context = new(StringComparer.Ordinal)
        {
            ["statusCode"] = ((int)response.StatusCode).ToString(System.Globalization.CultureInfo.InvariantCulture),
            ["httpVersion"] = response.Version.ToString(),
            ["contentLength"] = response.Content.Headers.ContentLength?.ToString(System.Globalization.CultureInfo.InvariantCulture),
            ["contentRange"] = response.Content.Headers.ContentRange?.ToString(),
            ["acceptRanges"] = string.Join(",", response.Headers.AcceptRanges)
        };
        AddDiagnosticHeader(context, response, "Content-Type");
        AddDiagnosticHeader(context, response, "Content-Length");
        AddDiagnosticHeader(context, response, "Content-Range");
        AddDiagnosticHeader(context, response, "Accept-Ranges");
        AddDiagnosticHeader(context, response, "ETag");
        AddDiagnosticHeader(context, response, "Last-Modified");
        AddDiagnosticHeader(context, response, "Retry-After");
        AddDiagnosticHeader(context, response, "Content-Disposition");
        return context;
    }

    private static void AddDiagnosticHeader(
        Dictionary<string, string?> context,
        HttpResponseMessage response,
        string name)
    {
        if (response.Headers.TryGetValues(name, out IEnumerable<string>? responseValues))
        {
            context[$"header.{name}"] = string.Join(", ", responseValues.Take(16));
            return;
        }

        if (response.Content.Headers.TryGetValues(name, out IEnumerable<string>? contentValues))
        {
            context[$"header.{name}"] = string.Join(", ", contentValues.Take(16));
        }
    }

    private static Dictionary<string, string?> CreateSegmentContext(DownloadSegment segment, long existingBytes)
        => new(StringComparer.Ordinal)
        {
            ["segmentIndex"] = segment.Index.ToString(System.Globalization.CultureInfo.InvariantCulture),
            ["segmentStart"] = segment.Start.ToString(System.Globalization.CultureInfo.InvariantCulture),
            ["segmentEnd"] = segment.End.ToString(System.Globalization.CultureInfo.InvariantCulture),
            ["segmentLength"] = segment.Length.ToString(System.Globalization.CultureInfo.InvariantCulture),
            ["segmentBytes"] = existingBytes.ToString(System.Globalization.CultureInfo.InvariantCulture)
        };

    private void Record(
        SegmentedDownloadContext context,
        TransferDiagnosticStage stage,
        TransferDiagnosticSeverity severity,
        string code,
        string message,
        IReadOnlyDictionary<string, string?>? details = null)
    {
        try
        {
            _diagnostics.Record(context.DownloadId, stage, severity, code, message, details);
        }
        catch
        {
            // Diagnostics must never interrupt a transfer.
        }
    }

    private sealed record ProbeResult(long TotalBytes, string? EntityTag, DateTimeOffset? LastModified);
}
