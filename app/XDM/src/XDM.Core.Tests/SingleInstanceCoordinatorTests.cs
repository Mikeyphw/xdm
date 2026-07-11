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

    private static int GetAvailablePort()
    {
        TcpListener listener = new(IPAddress.Loopback, 0);
        listener.Start();
        int port = ((IPEndPoint)listener.LocalEndpoint).Port;
        listener.Stop();
        return port;
    }
}
