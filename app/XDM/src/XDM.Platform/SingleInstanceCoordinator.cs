using System.Net;
using System.Net.Sockets;

namespace XDM.Platform;

public sealed class SingleInstanceCoordinator : IDisposable
{
    private readonly string _lockFilePath;
    private readonly int _activationPort;
    private FileStream? _lockStream;
    private TcpListener? _listener;
    private CancellationTokenSource? _listenerCancellation;
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

    public bool TryAcquire()
    {
        ObjectDisposedException.ThrowIf(_disposed, this);
        if (_lockStream is not null)
        {
            return true;
        }

        try
        {
            _lockStream = new FileStream(
                _lockFilePath,
                FileMode.OpenOrCreate,
                FileAccess.ReadWrite,
                FileShare.None,
                bufferSize: 1,
                FileOptions.DeleteOnClose);
            return true;
        }
        catch (IOException)
        {
            return false;
        }
        catch (UnauthorizedAccessException)
        {
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
            _ = ListenAsync(_listener, _listenerCancellation.Token);
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
        for (int attempt = 0; attempt < 20; attempt++)
        {
            try
            {
                using TcpClient client = new();
                await client.ConnectAsync(IPAddress.Loopback, _activationPort, cancellationToken).ConfigureAwait(false);
                byte[] signal = [1];
                await client.GetStream().WriteAsync(signal, cancellationToken).ConfigureAwait(false);
                return true;
            }
            catch (SocketException) when (attempt < 19)
            {
                await Task.Delay(TimeSpan.FromMilliseconds(150), cancellationToken).ConfigureAwait(false);
            }
            catch (IOException) when (attempt < 19)
            {
                await Task.Delay(TimeSpan.FromMilliseconds(150), cancellationToken).ConfigureAwait(false);
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

    public void Dispose()
    {
        if (_disposed)
        {
            return;
        }

        _disposed = true;
        _listenerCancellation?.Cancel();
        _listener?.Stop();
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
                byte[] signal = new byte[1];
                int bytesRead = await client.GetStream().ReadAsync(signal, cancellationToken).ConfigureAwait(false);
                if (bytesRead > 0)
                {
                    ActivationRequested?.Invoke(this, EventArgs.Empty);
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
            catch (IOException)
            {
                // A malformed activation connection must not stop the listener.
            }
        }
    }
}
