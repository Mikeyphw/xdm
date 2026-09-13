using System.Net;
using System.Net.Sockets;

using System.Security.Cryptography;

namespace XDM.Platform;

public enum SingleInstanceAcquireStatus
{
    NotAttempted,
    Acquired,
    AlreadyRunning,
    LockUnavailable
}

public sealed class SingleInstanceCoordinator : IDisposable
{
    private readonly string _lockFilePath;
    private readonly int _activationPort;
    private static readonly TimeSpan ActivationClientDeadline = TimeSpan.FromSeconds(2);
    private FileStream? _lockStream;
    private TcpListener? _listener;
    private CancellationTokenSource? _listenerCancellation;
    private Task? _listenerTask;
    private string? _lockToken;
    private SingleInstanceAcquireStatus _lastAcquireStatus;
    private bool _disposed;

    public SingleInstanceCoordinator(string applicationId, int activationPort)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(applicationId);
        ArgumentOutOfRangeException.ThrowIfLessThan(activationPort, 1);
        ArgumentOutOfRangeException.ThrowIfGreaterThan(activationPort, 65535);

        string stateDirectory = Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
            applicationId);
        Directory.CreateDirectory(stateDirectory);
        _lockFilePath = Path.Combine(stateDirectory, "instance.lock");
        _activationPort = activationPort;
    }

    public event EventHandler? ActivationRequested;

    public SingleInstanceAcquireStatus LastAcquireStatus => _lastAcquireStatus;

    public bool TryAcquire()
    {
        ObjectDisposedException.ThrowIf(_disposed, this);
        if (_lockStream is not null)
        {
            _lastAcquireStatus = SingleInstanceAcquireStatus.Acquired;
            return true;
        }

        try
        {
            _lockStream = new FileStream(
                _lockFilePath,
                FileMode.OpenOrCreate,
                FileAccess.ReadWrite,
                FileShare.Read,
                bufferSize: 1,
                FileOptions.DeleteOnClose);
            _lockToken = Convert.ToHexString(RandomNumberGenerator.GetBytes(32));
            byte[] token = System.Text.Encoding.ASCII.GetBytes(_lockToken);
            _lockStream.SetLength(0);
            _lockStream.Write(token);
            _lockStream.Flush(flushToDisk: true);
            _lastAcquireStatus = SingleInstanceAcquireStatus.Acquired;
            return true;
        }
        catch (IOException)
        {
            _lastAcquireStatus = SingleInstanceAcquireStatus.AlreadyRunning;
            return false;
        }
        catch (UnauthorizedAccessException)
        {
            _lastAcquireStatus = SingleInstanceAcquireStatus.LockUnavailable;
            return false;
        }
    }

    public bool StartListening()
    {
        ObjectDisposedException.ThrowIf(_disposed, this);
        if (_lockStream is null)
        {
            throw new InvalidOperationException("The instance lock must be acquired before starting activation listening.");
        }

        if (_listener is not null)
        {
            return true;
        }

        try
        {
            _listenerCancellation = new CancellationTokenSource();
            _listener = new TcpListener(IPAddress.Loopback, _activationPort);
            _listener.Start(backlog: 4);
            _listenerTask = ListenAsync(_listener, _listenerCancellation.Token);
            return true;
        }
        catch (SocketException)
        {
            _listenerCancellation?.Dispose();
            _listenerCancellation = null;
            _listener = null;
            return false;
        }
    }

    public async Task<bool> SignalPrimaryAsync(CancellationToken cancellationToken = default)
    {
        ObjectDisposedException.ThrowIf(_disposed, this);
        string? primaryToken = _lockToken ?? TryReadPrimaryLockToken();
        if (string.IsNullOrWhiteSpace(primaryToken))
        {
            return false;
        }

        byte[] signal = System.Text.Encoding.ASCII.GetBytes(primaryToken);
        for (int attempt = 0; attempt < 20; attempt++)
        {
            try
            {
                using CancellationTokenSource attemptCancellation = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
                attemptCancellation.CancelAfter(ActivationClientDeadline);
                using TcpClient client = new();
                await client.ConnectAsync(IPAddress.Loopback, _activationPort, attemptCancellation.Token).ConfigureAwait(false);
                NetworkStream stream = client.GetStream();
                await stream.WriteAsync(signal, attemptCancellation.Token).ConfigureAwait(false);
                byte[] acknowledgement = new byte[1];
                int acknowledged = await stream.ReadAsync(acknowledgement, attemptCancellation.Token).ConfigureAwait(false);
                if (acknowledged == 1 && acknowledgement[0] == 1)
                {
                    return true;
                }

                if (attempt < 19)
                {
                    await Task.Delay(TimeSpan.FromMilliseconds(150), cancellationToken).ConfigureAwait(false);
                }
            }
            catch (OperationCanceledException) when (!cancellationToken.IsCancellationRequested && attempt < 19)
            {
                await Task.Delay(TimeSpan.FromMilliseconds(150), cancellationToken).ConfigureAwait(false);
            }
            catch (SocketException) when (attempt < 19)
            {
                await Task.Delay(TimeSpan.FromMilliseconds(150), cancellationToken).ConfigureAwait(false);
            }
            catch (IOException) when (attempt < 19)
            {
                await Task.Delay(TimeSpan.FromMilliseconds(150), cancellationToken).ConfigureAwait(false);
            }
            catch (OperationCanceledException) when (!cancellationToken.IsCancellationRequested)
            {
                return false;
            }
            catch (SocketException)
            {
                return false;
            }
            catch (IOException)
            {
                return false;
            }
        }

        return false;
    }

    private string? TryReadPrimaryLockToken()
    {
        try
        {
            return File.ReadAllText(_lockFilePath).Trim();
        }
        catch (Exception exception) when (exception is IOException or UnauthorizedAccessException)
        {
            return null;
        }
    }

    public void Dispose()
    {
        if (_disposed)
        {
            return;
        }

        _disposed = true;
        _listenerCancellation?.Cancel();
        _listener?.Stop();
        try
        {
            _listenerTask?.Wait(ActivationClientDeadline);
        }
        catch (AggregateException)
        {
        }
        _listenerCancellation?.Dispose();
        _lockStream?.Dispose();
        ActivationRequested = null;
        GC.SuppressFinalize(this);
    }

    private async Task ListenAsync(TcpListener listener, CancellationToken cancellationToken)
    {
        while (!cancellationToken.IsCancellationRequested)
        {
            try
            {
                using TcpClient client = await listener.AcceptTcpClientAsync(cancellationToken).ConfigureAwait(false);
                using CancellationTokenSource readCancellation = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
                readCancellation.CancelAfter(ActivationClientDeadline);
                byte[] signal = new byte[64];
                int bytesRead = await client.GetStream().ReadAsync(signal, readCancellation.Token).ConfigureAwait(false);
                string supplied = System.Text.Encoding.ASCII.GetString(signal, 0, bytesRead);
                if (bytesRead > 0 && string.Equals(supplied, _lockToken, StringComparison.Ordinal))
                {
                    byte[] acknowledgement = new byte[] { 1 };
                    await client.GetStream().WriteAsync(acknowledgement, readCancellation.Token).ConfigureAwait(false);
                    try
                    {
                        ActivationRequested?.Invoke(this, EventArgs.Empty);
                    }
                    catch (Exception)
                    {
                        // Activation callbacks are UI-facing notifications and must not tear down the listener.
                    }
                }
            }
            catch (OperationCanceledException) when (cancellationToken.IsCancellationRequested)
            {
                break;
            }
            catch (SocketException) when (cancellationToken.IsCancellationRequested)
            {
                break;
            }
            catch (OperationCanceledException)
            {
                // One idle or malformed activation client must not monopolize the listener.
            }
            catch (IOException)
            {
                // A malformed activation connection must not stop the listener.
            }
        }
    }
}
