using System.Net;
using System.Net.Sockets;
using XDM.Platform;

namespace XDM.Core.Tests;

public sealed class SingleInstanceCoordinatorTests
{
    [Fact]
    public async Task SecondaryInstanceSignalsPrimaryInstance()
    {
        int port = GetAvailablePort();
        string applicationId = $"xdm-test-{Guid.NewGuid():N}";
        using SingleInstanceCoordinator primary = new(applicationId, port);
        using SingleInstanceCoordinator secondary = new(applicationId, port);
        TaskCompletionSource activation = new(TaskCreationOptions.RunContinuationsAsynchronously);
        primary.ActivationRequested += (_, _) => activation.TrySetResult();

        Assert.True(primary.TryAcquire());
        Assert.True(primary.StartListening());
        Assert.False(secondary.TryAcquire());
        Assert.True(await secondary.SignalPrimaryAsync());

        await activation.Task.WaitAsync(TimeSpan.FromSeconds(5));
    }

    [Fact]
    public async Task SecondaryInstanceRelaysFirefoxHandoffPayloadExactly()
    {
        int port = GetAvailablePort();
        string applicationId = $"xdm-test-{Guid.NewGuid():N}";
        string payload = "xdmdownload://capture?v=3&url=https%3A%2F%2Fcdn.example.test%2Fmaster.m3u8&title=Canonical%20Firefox";
        using SingleInstanceCoordinator primary = new(applicationId, port);
        using SingleInstanceCoordinator secondary = new(applicationId, port);
        TaskCompletionSource<string?> activation = new(TaskCreationOptions.RunContinuationsAsynchronously);
        primary.ActivationRequested += (_, args) => activation.TrySetResult(args.Payload);

        Assert.True(primary.TryAcquire());
        Assert.True(primary.StartListening());
        Assert.False(secondary.TryAcquire());
        Assert.True(await secondary.SignalPrimaryAsync(payload));

        Assert.Equal(payload, await activation.Task.WaitAsync(TimeSpan.FromSeconds(5)));
    }

    [Fact]
    public async Task IdleActivationClientCannotMonopolizeListener()
    {
        int port = GetAvailablePort();
        string applicationId = $"xdm-test-{Guid.NewGuid():N}";
        using SingleInstanceCoordinator primary = new(applicationId, port);
        using SingleInstanceCoordinator secondary = new(applicationId, port);
        TaskCompletionSource activation = new(TaskCreationOptions.RunContinuationsAsynchronously);
        primary.ActivationRequested += (_, _) => activation.TrySetResult();

        Assert.True(primary.TryAcquire());
        Assert.True(primary.StartListening());
        using TcpClient idle = new();
        await idle.ConnectAsync(IPAddress.Loopback, port);

        Assert.True(await secondary.SignalPrimaryAsync());
        await activation.Task.WaitAsync(TimeSpan.FromSeconds(5));
    }



    [Fact]
    public void ReleasedLockCanBeAcquiredByNewPrimary()
    {
        int port = GetAvailablePort();
        string applicationId = $"xdm-test-{Guid.NewGuid():N}";
        using SingleInstanceCoordinator first = new(applicationId, port);

        Assert.True(first.TryAcquire());
        Assert.Equal(SingleInstanceAcquireStatus.Acquired, first.LastAcquireStatus);
        first.Dispose();

        using SingleInstanceCoordinator replacement = new(applicationId, port);
        Assert.True(replacement.TryAcquire());
        Assert.Equal(SingleInstanceAcquireStatus.Acquired, replacement.LastAcquireStatus);
    }

    [Fact]
    public async Task PoisonedActivationPortDoesNotMasqueradeAsPrimary()
    {
        int port = GetAvailablePort();
        string applicationId = $"xdm-test-{Guid.NewGuid():N}";
        using TcpListener poisonedListener = new(IPAddress.Loopback, port);
        poisonedListener.Start();
        using CancellationTokenSource poisonCancellation = new(TimeSpan.FromSeconds(10));
        Task poisonTask = Task.Run(async () =>
        {
            while (!poisonCancellation.IsCancellationRequested)
            {
                try
                {
                    using TcpClient client = await poisonedListener.AcceptTcpClientAsync(poisonCancellation.Token);
                    byte[] buffer = new byte[64];
                    _ = await client.GetStream().ReadAsync(buffer, poisonCancellation.Token);
                }
                catch (OperationCanceledException)
                {
                    break;
                }
                catch (SocketException)
                {
                    break;
                }
                catch (ObjectDisposedException)
                {
                    break;
                }
                catch (IOException)
                {
                }
            }
        });

        using SingleInstanceCoordinator primary = new(applicationId, port);
        using SingleInstanceCoordinator secondary = new(applicationId, port);

        Assert.True(primary.TryAcquire());
        Assert.False(primary.StartListening());
        Assert.False(secondary.TryAcquire());
        Assert.False(await secondary.SignalPrimaryAsync());

        poisonCancellation.Cancel();
        poisonedListener.Stop();
        await poisonTask.WaitAsync(TimeSpan.FromSeconds(5));
    }

    private static int GetAvailablePort()
    {
        TcpListener listener = new(IPAddress.Loopback, 0);
        listener.Start();
        int port = ((IPEndPoint)listener.LocalEndpoint).Port;
        listener.Stop();
        return port;
    }
}
