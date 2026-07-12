using System.Net;
using System.Security.Cryptography;
using XDM.Core.Settings;

namespace XDM.DownloadEngine.Aria2;

public sealed class Aria2Service : IAria2Service, IDisposable
{
    private readonly ISettingsService _settingsService;
    private readonly Aria2ManagedProcess _managedProcess = new();
    private readonly SemaphoreSlim _gate = new(1, 1);
    private readonly CancellationTokenSource _lifetime = new();
    private HttpClient? _httpClient;
    private Aria2RpcClient? _rpcClient;
    private Aria2IntegrationSettings _settings = Aria2IntegrationSettings.Default;
    private Aria2ServiceSnapshot _current = Aria2ServiceSnapshot.Disabled;
    private Task? _pollingTask;
    private string? _managedSecret;
    private bool _initialized;
    private bool _disposed;

    public Aria2Service(ISettingsService settingsService)
    {
        ArgumentNullException.ThrowIfNull(settingsService);
        _settingsService = settingsService;
    }

    public Aria2ServiceSnapshot Current => _current;

    public event EventHandler<Aria2ServiceSnapshot>? Changed;

    public async Task InitializeAsync(CancellationToken cancellationToken = default)
    {
        ObjectDisposedException.ThrowIf(_disposed, this);
        if (_initialized)
        {
            return;
        }

        _initialized = true;
        _settingsService.Changed += OnSettingsChanged;
        await ConfigureCoreAsync(_settingsService.Current.Aria2, cancellationToken).ConfigureAwait(false);
        _pollingTask = PollAsync(_lifetime.Token);
    }

    public Task ConfigureAsync(
        Aria2IntegrationSettings settings,
        CancellationToken cancellationToken = default)
    {
        ArgumentNullException.ThrowIfNull(settings);
        ObjectDisposedException.ThrowIf(_disposed, this);
        return ConfigureCoreAsync(settings, cancellationToken);
    }

    public async Task StartManagedProcessAsync(CancellationToken cancellationToken = default)
    {
        ObjectDisposedException.ThrowIf(_disposed, this);
        await _gate.WaitAsync(cancellationToken).ConfigureAwait(false);
        try
        {
            Aria2IntegrationSettings requested = (_settingsService.Current.Aria2 ?? Aria2IntegrationSettings.Default).Normalize();
            if (requested.ConnectionMode != Aria2ConnectionMode.ManagedProcess)
            {
                throw new InvalidOperationException("Switch aria2 to managed-process mode before starting it.");
            }

            _settings = WithEffectiveManagedSecret(requested with { Enabled = true });
            CreateRpcClient(_settings);
            await _managedProcess.StartAsync(_settings, cancellationToken).ConfigureAwait(false);
        }
        finally
        {
            _gate.Release();
        }

        await WaitForManagedRpcAsync(cancellationToken).ConfigureAwait(false);
        await RefreshAsync(cancellationToken).ConfigureAwait(false);
    }

    public async Task StopManagedProcessAsync(CancellationToken cancellationToken = default)
    {
        ObjectDisposedException.ThrowIf(_disposed, this);
        await _gate.WaitAsync(cancellationToken).ConfigureAwait(false);
        try
        {
            if (_settings.ConnectionMode != Aria2ConnectionMode.ManagedProcess || !_managedProcess.IsRunning)
            {
                return;
            }

            if (_rpcClient is not null)
            {
                try
                {
                    if (_settings.SaveSession)
                    {
                        await _rpcClient.SaveSessionAsync(cancellationToken).ConfigureAwait(false);
                    }
                    await _rpcClient.ShutdownAsync(cancellationToken).ConfigureAwait(false);
                }
                catch (HttpRequestException)
                {
                }
                catch (Aria2RpcException)
                {
                }
            }

            await _managedProcess.StopAsync(cancellationToken).ConfigureAwait(false);
            Publish(new Aria2ServiceSnapshot(
                new Aria2Health(false, false, "Managed aria2 process is stopped."),
                _current.Tasks,
                DateTimeOffset.UtcNow,
                false));
        }
        finally
        {
            _gate.Release();
        }
    }

    public async Task RefreshAsync(CancellationToken cancellationToken = default)
    {
        ObjectDisposedException.ThrowIf(_disposed, this);
        await _gate.WaitAsync(cancellationToken).ConfigureAwait(false);
        try
        {
            if (!_settings.Enabled)
            {
                Publish(Aria2ServiceSnapshot.Disabled);
                return;
            }

            ValidateRemoteEndpointSecurity(_settings);
            Aria2RpcClient client = _rpcClient ?? throw new InvalidOperationException("aria2 RPC is not configured.");
            Publish(_current with { IsRefreshing = true });
            try
            {
                string version = await client.GetVersionAsync(cancellationToken).ConfigureAwait(false);
                Task<IReadOnlyList<Aria2TaskSnapshot>> activeTask = client.TellActiveAsync(cancellationToken);
                Task<IReadOnlyList<Aria2TaskSnapshot>> waitingTask = client.TellWaitingAsync(cancellationToken: cancellationToken);
                Task<IReadOnlyList<Aria2TaskSnapshot>> stoppedTask = client.TellStoppedAsync(cancellationToken: cancellationToken);
                await Task.WhenAll(activeTask, waitingTask, stoppedTask).ConfigureAwait(false);
                Aria2TaskSnapshot[] tasks = activeTask.Result
                    .Concat(waitingTask.Result)
                    .Concat(stoppedTask.Result)
                    .DistinctBy(static task => task.Gid, StringComparer.Ordinal)
                    .ToArray();
                Publish(new Aria2ServiceSnapshot(
                    new Aria2Health(
                        true,
                        _managedProcess.IsRunning,
                        $"aria2 {version} is connected.",
                        version),
                    tasks,
                    DateTimeOffset.UtcNow,
                    false));
            }
            catch (OperationCanceledException) when (!cancellationToken.IsCancellationRequested)
            {
                PublishFailure("The aria2 RPC request timed out.");
            }
            catch (HttpRequestException exception)
            {
                PublishFailure($"Unable to reach aria2 RPC: {exception.Message}");
            }
            catch (Aria2RpcException exception)
            {
                PublishFailure(exception.Message);
            }
            catch (InvalidDataException exception)
            {
                PublishFailure($"Invalid aria2 RPC response: {exception.Message}");
            }
        }
        finally
        {
            _gate.Release();
        }
    }

    public async Task<string> AddAsync(
        Aria2AddRequest request,
        CancellationToken cancellationToken = default)
    {
        ObjectDisposedException.ThrowIf(_disposed, this);
        string gid;
        await _gate.WaitAsync(cancellationToken).ConfigureAwait(false);
        try
        {
            EnsureEnabled();
            Aria2RpcClient client = _rpcClient ?? throw new InvalidOperationException("aria2 RPC is not configured.");
            gid = await client.AddUriAsync(
                request,
                _settings.SplitCount,
                _settings.MinimumSplitSizeBytes,
                cancellationToken).ConfigureAwait(false);
        }
        finally
        {
            _gate.Release();
        }

        await RefreshAsync(cancellationToken).ConfigureAwait(false);
        return gid;
    }

    public Task PauseAsync(string gid, CancellationToken cancellationToken = default)
        => ExecuteTaskCommandAsync(gid, static (client, taskId, token) => client.PauseAsync(taskId, token), cancellationToken);

    public Task ResumeAsync(string gid, CancellationToken cancellationToken = default)
        => ExecuteTaskCommandAsync(gid, static (client, taskId, token) => client.ResumeAsync(taskId, token), cancellationToken);

    public Task RemoveAsync(string gid, CancellationToken cancellationToken = default)
        => ExecuteTaskCommandAsync(gid, RemoveTaskAsync, cancellationToken);

    public void Dispose()
    {
        if (_disposed)
        {
            return;
        }

        _disposed = true;
        _settingsService.Changed -= OnSettingsChanged;
        _lifetime.Cancel();
        try
        {
            _pollingTask?.GetAwaiter().GetResult();
        }
        catch (OperationCanceledException)
        {
        }
        _httpClient?.Dispose();
        _managedProcess.Dispose();
        _gate.Dispose();
        _lifetime.Dispose();
        GC.SuppressFinalize(this);
    }

    private async Task ExecuteTaskCommandAsync(
        string gid,
        Func<Aria2RpcClient, string, CancellationToken, Task> command,
        CancellationToken cancellationToken)
    {
        ObjectDisposedException.ThrowIf(_disposed, this);
        await _gate.WaitAsync(cancellationToken).ConfigureAwait(false);
        try
        {
            EnsureEnabled();
            Aria2RpcClient client = _rpcClient ?? throw new InvalidOperationException("aria2 RPC is not configured.");
            await command(client, gid, cancellationToken).ConfigureAwait(false);
        }
        finally
        {
            _gate.Release();
        }

        await RefreshAsync(cancellationToken).ConfigureAwait(false);
    }

    private static async Task RemoveTaskAsync(
        Aria2RpcClient client,
        string gid,
        CancellationToken cancellationToken)
    {
        try
        {
            await client.RemoveAsync(gid, cancellationToken).ConfigureAwait(false);
        }
        catch (Aria2RpcException exception) when (exception.Code is 1 or 2)
        {
            await client.ForceRemoveAsync(gid, cancellationToken).ConfigureAwait(false);
        }
    }

    private async Task ConfigureCoreAsync(
        Aria2IntegrationSettings? requestedSettings,
        CancellationToken cancellationToken)
    {
        await _gate.WaitAsync(cancellationToken).ConfigureAwait(false);
        try
        {
            Aria2IntegrationSettings normalized = (requestedSettings ?? Aria2IntegrationSettings.Default).Normalize();
            Aria2IntegrationSettings nextSettings = normalized.ConnectionMode == Aria2ConnectionMode.ManagedProcess
                ? WithEffectiveManagedSecret(normalized)
                : normalized;
            bool stopExistingManagedProcess = _managedProcess.IsRunning
                && (!nextSettings.Enabled
                    || nextSettings.ConnectionMode != Aria2ConnectionMode.ManagedProcess
                    || !HasSameManagedProcessConfiguration(_settings, nextSettings));
            if (stopExistingManagedProcess)
            {
                await StopExistingManagedProcessCoreAsync(cancellationToken).ConfigureAwait(false);
            }

            _settings = nextSettings;
            CreateRpcClient(_settings);

            if (!_settings.Enabled)
            {
                Publish(Aria2ServiceSnapshot.Disabled);
                return;
            }

            ValidateRemoteEndpointSecurity(_settings);
            if (_settings.ConnectionMode == Aria2ConnectionMode.ManagedProcess
                && _settings.AutoStartManagedProcess
                && !_managedProcess.IsRunning)
            {
                await _managedProcess.StartAsync(_settings, cancellationToken).ConfigureAwait(false);
            }
        }
        catch (Exception exception) when (exception is IOException
            or UnauthorizedAccessException
            or InvalidOperationException
            or System.ComponentModel.Win32Exception)
        {
            PublishFailure($"aria2 configuration failed: {exception.Message}");
            return;
        }
        finally
        {
            _gate.Release();
        }

        await RefreshAsync(cancellationToken).ConfigureAwait(false);
    }

    private void CreateRpcClient(Aria2IntegrationSettings settings)
    {
        _httpClient?.Dispose();
        SocketsHttpHandler handler = new()
        {
            UseProxy = false,
            AutomaticDecompression = DecompressionMethods.All,
            ConnectTimeout = TimeSpan.FromSeconds(settings.RpcConnectTimeoutSeconds)
        };
        _httpClient = new HttpClient(handler, disposeHandler: true)
        {
            Timeout = TimeSpan.FromSeconds(settings.RpcConnectTimeoutSeconds)
        };
        _rpcClient = new Aria2RpcClient(_httpClient, settings.GetRpcUri(), settings.RpcSecret);
    }

    private async Task WaitForManagedRpcAsync(CancellationToken cancellationToken)
    {
        DateTimeOffset deadline = DateTimeOffset.UtcNow.AddSeconds(_settings.RpcConnectTimeoutSeconds);
        Exception? lastFailure = null;
        while (DateTimeOffset.UtcNow < deadline)
        {
            cancellationToken.ThrowIfCancellationRequested();
            try
            {
                if (_rpcClient is not null)
                {
                    _ = await _rpcClient.GetVersionAsync(cancellationToken).ConfigureAwait(false);
                    return;
                }
            }
            catch (HttpRequestException exception)
            {
                lastFailure = exception;
            }
            catch (TaskCanceledException exception) when (!cancellationToken.IsCancellationRequested)
            {
                lastFailure = exception;
            }

            await Task.Delay(150, cancellationToken).ConfigureAwait(false);
        }

        string detail = _managedProcess.LastError ?? lastFailure?.Message ?? "RPC did not become ready.";
        throw new InvalidOperationException($"Managed aria2 did not become ready: {detail}", lastFailure);
    }

    private async Task PollAsync(CancellationToken cancellationToken)
    {
        while (!cancellationToken.IsCancellationRequested)
        {
            int interval = _settings.PollIntervalMilliseconds;
            try
            {
                await Task.Delay(interval, cancellationToken).ConfigureAwait(false);
                if (_settings.Enabled)
                {
                    await RefreshAsync(cancellationToken).ConfigureAwait(false);
                }
            }
            catch (OperationCanceledException) when (cancellationToken.IsCancellationRequested)
            {
                return;
            }
        }
    }

    private void OnSettingsChanged(object? sender, ApplicationSettings settings)
        => _ = ReconfigureAfterSettingsChangeAsync(settings.Aria2);

    private async Task ReconfigureAfterSettingsChangeAsync(Aria2IntegrationSettings? settings)
    {
        try
        {
            await ConfigureCoreAsync(settings, _lifetime.Token).ConfigureAwait(false);
        }
        catch (OperationCanceledException) when (_lifetime.IsCancellationRequested)
        {
        }
    }

    private void EnsureEnabled()
    {
        if (!_settings.Enabled)
        {
            throw new InvalidOperationException("aria2 integration is disabled.");
        }
        ValidateRemoteEndpointSecurity(_settings);
    }

    private static void ValidateRemoteEndpointSecurity(Aria2IntegrationSettings settings)
    {
        Uri endpoint = settings.GetRpcUri();
        if (!endpoint.IsLoopback && endpoint.Scheme != Uri.UriSchemeHttps)
        {
            throw new InvalidOperationException("Remote aria2 RPC endpoints must use HTTPS.");
        }
        if (!endpoint.IsLoopback && string.IsNullOrWhiteSpace(settings.RpcSecret))
        {
            throw new InvalidOperationException("Remote aria2 RPC endpoints require an RPC secret.");
        }
    }

    private Aria2IntegrationSettings WithEffectiveManagedSecret(Aria2IntegrationSettings settings)
    {
        if (settings.RpcSecret.Length > 0)
        {
            _managedSecret = settings.RpcSecret;
            return settings;
        }

        _managedSecret ??= Convert.ToHexString(RandomNumberGenerator.GetBytes(24));
        return settings with { RpcSecret = _managedSecret };
    }

    private async Task StopExistingManagedProcessCoreAsync(CancellationToken cancellationToken)
    {
        if (_rpcClient is not null)
        {
            try
            {
                if (_settings.SaveSession)
                {
                    await _rpcClient.SaveSessionAsync(cancellationToken).ConfigureAwait(false);
                }
                await _rpcClient.ShutdownAsync(cancellationToken).ConfigureAwait(false);
            }
            catch (HttpRequestException)
            {
            }
            catch (Aria2RpcException)
            {
            }
        }

        await _managedProcess.StopAsync(cancellationToken).ConfigureAwait(false);
    }

    private static bool HasSameManagedProcessConfiguration(
        Aria2IntegrationSettings left,
        Aria2IntegrationSettings right)
        => string.Equals(left.RpcEndpoint, right.RpcEndpoint, StringComparison.OrdinalIgnoreCase)
            && string.Equals(left.RpcSecret, right.RpcSecret, StringComparison.Ordinal)
            && string.Equals(left.ExecutablePath, right.ExecutablePath, StringComparison.Ordinal)
            && string.Equals(left.SessionFilePath, right.SessionFilePath, StringComparison.Ordinal)
            && left.MaxConcurrentDownloads == right.MaxConcurrentDownloads
            && left.SplitCount == right.SplitCount
            && left.MinimumSplitSizeBytes == right.MinimumSplitSizeBytes
            && string.Equals(left.AdditionalArguments, right.AdditionalArguments, StringComparison.Ordinal)
            && left.ContinueDownloads == right.ContinueDownloads
            && left.CheckCertificate == right.CheckCertificate
            && left.SaveSession == right.SaveSession;

    private void PublishFailure(string message)
    {
        string? processError = _managedProcess.LastError;
        string finalMessage = string.IsNullOrWhiteSpace(processError)
            ? message
            : $"{message} aria2c: {processError}";
        Publish(new Aria2ServiceSnapshot(
            new Aria2Health(false, _managedProcess.IsRunning, finalMessage),
            _current.Tasks,
            DateTimeOffset.UtcNow,
            false));
    }

    private void Publish(Aria2ServiceSnapshot snapshot)
    {
        _current = snapshot;
        Changed?.Invoke(this, snapshot);
    }
}
