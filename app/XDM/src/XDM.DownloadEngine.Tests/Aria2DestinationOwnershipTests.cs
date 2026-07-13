using XDM.DownloadEngine.Aria2;
using XDM.DownloadEngine.Backends;

namespace XDM.DownloadEngine.Tests;

public sealed class Aria2DestinationOwnershipTests
{
    [Fact]
    public void FindsExactDestinationCollision()
    {
        string path = Path.Combine(Path.GetTempPath(), "xdm-owned.bin");
        Aria2TaskSnapshot task = CreateTask("gid-one", path);

        Aria2TaskSnapshot? collision = Aria2DestinationOwnership.FindCollision(path, [task]);

        Assert.Same(task, collision);
    }

    [Fact]
    public void IgnoresTaskAlreadyOwnedByDownload()
    {
        string path = Path.Combine(Path.GetTempPath(), "xdm-owned.bin");
        Aria2TaskSnapshot task = CreateTask("gid-one", path);

        Aria2TaskSnapshot? collision = Aria2DestinationOwnership.FindCollision(
            path,
            [task],
            ownedGid: "gid-one");

        Assert.Null(collision);
    }

    [Theory]
    [InlineData(Aria2TaskStatus.Complete)]
    [InlineData(Aria2TaskStatus.Error)]
    [InlineData(Aria2TaskStatus.Removed)]
    public void IgnoresTerminalTasksThatNoLongerOwnDestination(Aria2TaskStatus status)
    {
        string path = Path.Combine(Path.GetTempPath(), "xdm-terminal.bin");
        Aria2TaskSnapshot task = CreateTask("gid-terminal", path) with { Status = status };

        Aria2TaskSnapshot? collision = Aria2DestinationOwnership.FindCollision(path, [task]);

        Assert.Null(collision);
    }

    private static Aria2TaskSnapshot CreateTask(string gid, string path)
        => new(
            gid,
            Aria2TaskStatus.Active,
            Path.GetFileName(path),
            path,
            0,
            100,
            0,
            0,
            1,
            null,
            null);
}
