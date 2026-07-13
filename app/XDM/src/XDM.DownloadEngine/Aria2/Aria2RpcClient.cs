using System.Net.Http.Headers;
using System.Text;
using System.Text.Json;

namespace XDM.DownloadEngine.Aria2;

public sealed class Aria2RpcClient
{
    private static readonly string[] TaskKeys =
    [
        "gid",
        "status",
        "totalLength",
        "completedLength",
        "downloadSpeed",
        "uploadSpeed",
        "connections",
        "errorCode",
        "errorMessage",
        "dir",
        "files",
        "bittorrent"
    ];

    private readonly HttpClient _httpClient;
    private readonly Uri _endpoint;
    private readonly string _secret;
    private long _requestId;

    public Aria2RpcClient(HttpClient httpClient, Uri endpoint, string? secret)
    {
        ArgumentNullException.ThrowIfNull(httpClient);
        ArgumentNullException.ThrowIfNull(endpoint);
        if (!endpoint.IsAbsoluteUri
            || (endpoint.Scheme != Uri.UriSchemeHttp && endpoint.Scheme != Uri.UriSchemeHttps))
        {
            throw new ArgumentException("The aria2 RPC endpoint must be an absolute HTTP or HTTPS URI.", nameof(endpoint));
        }

        _httpClient = httpClient;
        _endpoint = endpoint;
        _secret = secret?.Trim() ?? string.Empty;
    }

    public async Task<string> GetVersionAsync(CancellationToken cancellationToken = default)
    {
        JsonElement result = await InvokeAsync("aria2.getVersion", [], cancellationToken).ConfigureAwait(false);
        return result.TryGetProperty("version", out JsonElement version)
            ? version.GetString() ?? "unknown"
            : "unknown";
    }

    public async Task<string> AddUriAsync(
        Aria2AddRequest request,
        int splitCount,
        long minimumSplitSizeBytes,
        CancellationToken cancellationToken = default)
    {
        Aria2AddRequest normalized = request.Normalize();
        Dictionary<string, object?> options = new(StringComparer.Ordinal)
        {
            ["dir"] = normalized.DestinationDirectory,
            ["continue"] = "true",
            ["split"] = Math.Clamp(splitCount, 1, 64).ToString(System.Globalization.CultureInfo.InvariantCulture),
            ["max-connection-per-server"] = Math.Clamp(splitCount, 1, 64).ToString(System.Globalization.CultureInfo.InvariantCulture),
            ["min-split-size"] = Math.Max(1024 * 1024, minimumSplitSizeBytes).ToString(System.Globalization.CultureInfo.InvariantCulture)
        };
        if (!string.IsNullOrWhiteSpace(normalized.FileName))
        {
            options["out"] = normalized.FileName;
        }
        if (normalized.Headers is { Count: > 0 })
        {
            options["header"] = normalized.Headers.Select(static pair => $"{pair.Key}: {pair.Value}").ToArray();
        }
        if (!string.IsNullOrWhiteSpace(normalized.Username))
        {
            options["http-user"] = normalized.Username;
            options["ftp-user"] = normalized.Username;
        }
        if (normalized.Password is not null)
        {
            options["http-passwd"] = normalized.Password;
            options["ftp-passwd"] = normalized.Password;
        }
        if (normalized.SpeedLimitBytesPerSecond is long speedLimit)
        {
            options["max-download-limit"] = speedLimit.ToString(System.Globalization.CultureInfo.InvariantCulture);
        }
        if (normalized.ExpectedChecksumAlgorithm is string algorithm
            && normalized.ExpectedChecksum is string checksum)
        {
            options["checksum"] = $"{algorithm}={checksum}";
        }

        JsonElement result = await InvokeAsync(
            "aria2.addUri",
            [normalized.GetSources().Select(static uri => uri.AbsoluteUri).ToArray(), options],
            cancellationToken).ConfigureAwait(false);
        return result.GetString()
            ?? throw new InvalidDataException("aria2.addUri returned an empty task identifier.");
    }

    public async Task<Aria2TaskSnapshot> TellStatusAsync(
        string gid,
        CancellationToken cancellationToken = default)
    {
        EnsureGid(gid);
        JsonElement result = await InvokeAsync(
            "aria2.tellStatus",
            [gid, TaskKeys],
            cancellationToken).ConfigureAwait(false);
        return ParseTask(result);
    }

    public async Task<IReadOnlyList<Aria2TaskSnapshot>> TellActiveAsync(
        CancellationToken cancellationToken = default)
    {
        JsonElement result = await InvokeAsync(
            "aria2.tellActive",
            [TaskKeys],
            cancellationToken).ConfigureAwait(false);
        return ParseTasks(result);
    }

    public async Task<IReadOnlyList<Aria2TaskSnapshot>> TellWaitingAsync(
        int offset = 0,
        int count = 100,
        CancellationToken cancellationToken = default)
    {
        JsonElement result = await InvokeAsync(
            "aria2.tellWaiting",
            [offset, Math.Clamp(count, 1, 1000), TaskKeys],
            cancellationToken).ConfigureAwait(false);
        return ParseTasks(result);
    }

    public async Task<IReadOnlyList<Aria2TaskSnapshot>> TellStoppedAsync(
        int offset = 0,
        int count = 100,
        CancellationToken cancellationToken = default)
    {
        JsonElement result = await InvokeAsync(
            "aria2.tellStopped",
            [offset, Math.Clamp(count, 1, 1000), TaskKeys],
            cancellationToken).ConfigureAwait(false);
        return ParseTasks(result);
    }

    public Task PauseAsync(string gid, CancellationToken cancellationToken = default)
        => InvokeDiscardingResultAsync("aria2.pause", gid, cancellationToken);

    public Task ResumeAsync(string gid, CancellationToken cancellationToken = default)
        => InvokeDiscardingResultAsync("aria2.unpause", gid, cancellationToken);

    public Task RemoveAsync(string gid, CancellationToken cancellationToken = default)
        => InvokeDiscardingResultAsync("aria2.remove", gid, cancellationToken);

    public Task ForceRemoveAsync(string gid, CancellationToken cancellationToken = default)
        => InvokeDiscardingResultAsync("aria2.forceRemove", gid, cancellationToken);

    public async Task SaveSessionAsync(CancellationToken cancellationToken = default)
        => _ = await InvokeAsync("aria2.saveSession", [], cancellationToken).ConfigureAwait(false);

    public async Task ShutdownAsync(CancellationToken cancellationToken = default)
        => _ = await InvokeAsync("aria2.shutdown", [], cancellationToken).ConfigureAwait(false);

    private async Task InvokeDiscardingResultAsync(
        string method,
        string gid,
        CancellationToken cancellationToken)
    {
        EnsureGid(gid);
        _ = await InvokeAsync(method, [gid], cancellationToken).ConfigureAwait(false);
    }

    private async Task<JsonElement> InvokeAsync(
        string method,
        IReadOnlyList<object?> parameters,
        CancellationToken cancellationToken)
    {
        List<object?> authenticatedParameters = new(parameters.Count + (_secret.Length == 0 ? 0 : 1));
        if (_secret.Length > 0)
        {
            authenticatedParameters.Add($"token:{_secret}");
        }
        authenticatedParameters.AddRange(parameters);

        Dictionary<string, object?> request = new(StringComparer.Ordinal)
        {
            ["jsonrpc"] = "2.0",
            ["id"] = Interlocked.Increment(ref _requestId).ToString(System.Globalization.CultureInfo.InvariantCulture),
            ["method"] = method,
            ["params"] = authenticatedParameters
        };

        byte[] payload = JsonSerializer.SerializeToUtf8Bytes(request);
        using ByteArrayContent content = new(payload);
        content.Headers.ContentType = new MediaTypeHeaderValue("application/json")
        {
            CharSet = Encoding.UTF8.WebName
        };
        using HttpResponseMessage response = await _httpClient.PostAsync(
            _endpoint,
            content,
            cancellationToken).ConfigureAwait(false);
        response.EnsureSuccessStatusCode();

        await using Stream stream = await response.Content.ReadAsStreamAsync(cancellationToken).ConfigureAwait(false);
        using JsonDocument document = await JsonDocument.ParseAsync(stream, cancellationToken: cancellationToken)
            .ConfigureAwait(false);
        JsonElement root = document.RootElement;
        if (root.TryGetProperty("error", out JsonElement error))
        {
            int code = error.TryGetProperty("code", out JsonElement codeElement) && codeElement.TryGetInt32(out int parsedCode)
                ? parsedCode
                : -1;
            string message = error.TryGetProperty("message", out JsonElement messageElement)
                ? messageElement.GetString() ?? "Unknown aria2 RPC failure."
                : "Unknown aria2 RPC failure.";
            throw new Aria2RpcException(code, message);
        }

        if (!root.TryGetProperty("result", out JsonElement result))
        {
            throw new InvalidDataException("The aria2 RPC response did not contain a result.");
        }

        return result.Clone();
    }

    private static Aria2TaskSnapshot[] ParseTasks(JsonElement result)
    {
        if (result.ValueKind != JsonValueKind.Array)
        {
            throw new InvalidDataException("The aria2 task-list response was not an array.");
        }

        return result.EnumerateArray().Select(ParseTask).ToArray();
    }

    internal static Aria2TaskSnapshot ParseTask(JsonElement task)
    {
        string gid = GetString(task, "gid") ?? "unknown";
        Aria2TaskStatus status = ParseStatus(GetString(task, "status"));
        long total = GetLong(task, "totalLength");
        long completed = GetLong(task, "completedLength");
        string? path = null;
        string? source = null;
        if (task.TryGetProperty("files", out JsonElement files)
            && files.ValueKind == JsonValueKind.Array
            && files.GetArrayLength() > 0)
        {
            JsonElement file = files[0];
            path = GetString(file, "path");
            if (file.TryGetProperty("uris", out JsonElement uris)
                && uris.ValueKind == JsonValueKind.Array
                && uris.GetArrayLength() > 0)
            {
                source = GetString(uris[0], "uri");
            }
        }

        string? torrentName = null;
        if (task.TryGetProperty("bittorrent", out JsonElement bittorrent)
            && bittorrent.ValueKind == JsonValueKind.Object
            && bittorrent.TryGetProperty("info", out JsonElement info))
        {
            torrentName = GetString(info, "name");
        }

        string displayName = FirstNonEmpty(
            torrentName,
            path is null ? null : Path.GetFileName(path),
            source,
            gid);
        return new Aria2TaskSnapshot(
            gid,
            status,
            displayName,
            path,
            completed,
            total,
            GetLong(task, "downloadSpeed"),
            GetLong(task, "uploadSpeed"),
            checked((int)Math.Clamp(GetLong(task, "connections"), 0, int.MaxValue)),
            GetString(task, "errorCode"),
            GetString(task, "errorMessage"));
    }

    private static Aria2TaskStatus ParseStatus(string? value)
        => value?.ToLowerInvariant() switch
        {
            "waiting" => Aria2TaskStatus.Waiting,
            "active" => Aria2TaskStatus.Active,
            "paused" => Aria2TaskStatus.Paused,
            "complete" => Aria2TaskStatus.Complete,
            "error" => Aria2TaskStatus.Error,
            "removed" => Aria2TaskStatus.Removed,
            _ => Aria2TaskStatus.Unknown
        };

    private static string? GetString(JsonElement element, string propertyName)
        => element.ValueKind == JsonValueKind.Object
            && element.TryGetProperty(propertyName, out JsonElement property)
            && property.ValueKind != JsonValueKind.Null
                ? property.ToString()
                : null;

    private static long GetLong(JsonElement element, string propertyName)
    {
        string? value = GetString(element, propertyName);
        return long.TryParse(
            value,
            System.Globalization.NumberStyles.Integer,
            System.Globalization.CultureInfo.InvariantCulture,
            out long result)
                ? Math.Max(0, result)
                : 0;
    }

    private static string FirstNonEmpty(params string?[] values)
        => values.First(static value => !string.IsNullOrWhiteSpace(value))!;

    private static void EnsureGid(string gid)
    {
        if (string.IsNullOrWhiteSpace(gid))
        {
            throw new ArgumentException("An aria2 task identifier is required.", nameof(gid));
        }
    }
}
