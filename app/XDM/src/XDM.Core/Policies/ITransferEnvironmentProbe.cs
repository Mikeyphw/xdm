namespace XDM.Core.Policies;

public interface ITransferEnvironmentProbe
{
    Task<TransferEnvironmentSnapshot> GetSnapshotAsync(CancellationToken cancellationToken = default);
}
