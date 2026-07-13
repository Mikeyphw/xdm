using XDM.Core.Downloads;
using XDM.Core.Settings;
using XDM.DownloadEngine.Aria2;

namespace XDM.DownloadEngine.Backends;

public static class DownloadBackendAdvisor
{
    public static DownloadBackendDecision Decide(
        DownloadRequest request,
        Aria2IntegrationSettings settings,
        Aria2ServiceSnapshot snapshot)
    {
        ArgumentNullException.ThrowIfNull(request);
        ArgumentNullException.ThrowIfNull(settings);
        ArgumentNullException.ThrowIfNull(snapshot);

        string method = string.IsNullOrWhiteSpace(request.Method)
            ? "GET"
            : request.Method.Trim().ToUpperInvariant();
        bool supportedRequest = method == "GET"
            && request.RequestBody is null
            && request.Source.IsAbsoluteUri
            && request.Source.Scheme is "http" or "https" or "ftp";
        if (!supportedRequest)
        {
            return request.BackendPreference == DownloadBackendPreference.Aria2
                ? FallbackOrForcedFailure(
                    request,
                    settings.AllowNativeFallback,
                    "This request requires the native backend because aria2 routing supports replayable GET transfers only.")
                : new DownloadBackendDecision(DownloadBackendKind.Native, "The native backend is required for this request type.");
        }

        bool aria2Ready = settings.Enabled && snapshot.Health.IsAvailable;
        if (request.BackendPreference == DownloadBackendPreference.Native)
        {
            return new DownloadBackendDecision(DownloadBackendKind.Native, "The native backend was selected for this download.");
        }

        if (request.BackendPreference == DownloadBackendPreference.Aria2)
        {
            return aria2Ready
                ? new DownloadBackendDecision(DownloadBackendKind.Aria2, "aria2 was selected for this download.")
                : FallbackOrForcedFailure(request, settings.AllowNativeFallback, snapshot.Health.Message);
        }

        if (!settings.AutomaticRoutingEnabled)
        {
            return new DownloadBackendDecision(DownloadBackendKind.Native, "Automatic aria2 routing is disabled.");
        }

        if (!aria2Ready)
        {
            return new DownloadBackendDecision(DownloadBackendKind.Native, $"aria2 is unavailable: {snapshot.Health.Message}", IsFallback: true);
        }

        int sourceCount = 1 + (request.Mirrors?.Count ?? 0);
        if (settings.PreferForMirrors && sourceCount > 1)
        {
            return new DownloadBackendDecision(DownloadBackendKind.Aria2, $"aria2 was recommended for {sourceCount} available mirrors.");
        }

        if (request.ExpectedLength is long expectedLength
            && expectedLength >= settings.AutomaticRoutingMinimumBytes)
        {
            return new DownloadBackendDecision(DownloadBackendKind.Aria2, "aria2 was recommended for this large transfer.");
        }

        if (request.ConnectionCount >= settings.AutomaticRoutingMinimumConnections)
        {
            return new DownloadBackendDecision(DownloadBackendKind.Aria2, "aria2 was recommended for the requested connection count.");
        }

        if (request.Source.Scheme == "ftp")
        {
            return new DownloadBackendDecision(DownloadBackendKind.Aria2, "aria2 was recommended for this transfer protocol.");
        }

        return new DownloadBackendDecision(DownloadBackendKind.Native, "The native backend is the best fit for this transfer.");
    }

    private static DownloadBackendDecision FallbackOrForcedFailure(
        DownloadRequest request,
        bool globalFallbackAllowed,
        string reason)
        => request.AllowBackendFallback && globalFallbackAllowed
            ? new DownloadBackendDecision(DownloadBackendKind.Native, $"Fell back to the native backend: {reason}", IsFallback: true)
            : new DownloadBackendDecision(
                DownloadBackendKind.Aria2,
                reason,
                CanStart: false);
}
