using System.ComponentModel;
using System.Diagnostics;
using System.IO.Compression;
using System.Security.Cryptography;
using System.Text.Json;
using XDM.Core.Product;

namespace XDM.Updater;

public sealed class UpdateTransactionExecutor
{
    private readonly bool _launchApplication;
    private const int MaximumEntries = 100_000;
    private const long MaximumPackageBytes = 2L * 1024 * 1024 * 1024;
    private const long MaximumExpandedBytes = 4L * 1024 * 1024 * 1024;
    private static readonly TimeSpan OldProcessExitTimeout = TimeSpan.FromSeconds(45);
    private static readonly TimeSpan HealthObservationWindow = TimeSpan.FromSeconds(30);
    private static readonly TimeSpan PostObservationGraceWindow = TimeSpan.FromSeconds(20);
    private static readonly StringComparison PathComparison = OperatingSystem.IsWindows()
        ? StringComparison.OrdinalIgnoreCase
        : StringComparison.Ordinal;
    private static readonly JsonSerializerOptions JsonOptions = new(JsonSerializerDefaults.Web)
    {
        WriteIndented = true
    };

    public UpdateTransactionExecutor(bool launchApplication = true)
    {
        _launchApplication = launchApplication;
    }

    public async Task ApplyAsync(
        string transactionPath,
        int? waitProcessId = null,
        CancellationToken cancellationToken = default)
    {
        UpdateTransactionDocument transaction = await ReadAsync(transactionPath, cancellationToken)
            .ConfigureAwait(false);
        await RecoverInterruptedTransactionAsync(transactionPath, transaction, cancellationToken)
            .ConfigureAwait(false);
        transaction = await ReadAsync(transactionPath, cancellationToken).ConfigureAwait(false);
        ValidateTransactionPaths(transaction);
        if (transaction.State is not (UpdateTransactionState.Staged or UpdateTransactionState.Failed))
        {
            throw new InvalidOperationException($"Transaction state {transaction.State} cannot be applied.");
        }
        if (!string.Equals(transaction.InstallModel ?? "portable-self-update", "portable-self-update", StringComparison.OrdinalIgnoreCase))
        {
            throw new InvalidOperationException("Only portable self-update transactions may be applied by XDM.Updater.");
        }
        if (waitProcessId is int pid)
        {
            await WaitForProcessExitAsync(pid, OldProcessExitTimeout, cancellationToken).ConfigureAwait(false);
        }

        await VerifyPackageAsync(transaction, cancellationToken).ConfigureAwait(false);
        string extractingPath = $"{transaction.CandidatePath}.extracting";
        DeleteDirectoryIfPresent(extractingPath);
        DeleteDirectoryIfPresent(transaction.CandidatePath);
        Directory.CreateDirectory(extractingPath);
        bool installationMovedToBackup = false;
        try
        {
            ExtractPortableZip(transaction.PackagePath, extractingPath);
            ValidateCandidate(extractingPath, transaction.ExecutableRelativePath);
            EnsureUnixExecutables(extractingPath, transaction.ExecutableRelativePath);
            Directory.Move(extractingPath, transaction.CandidatePath);
            transaction = transaction with
            {
                State = UpdateTransactionState.Applying,
                UpdatedAtUtc = DateTimeOffset.UtcNow,
                FailureMessage = null,
                RecoveryMarkerPath = transaction.RecoveryMarkerPath ?? Path.Combine(
                    Directory.GetParent(transaction.InstallRoot)?.FullName ?? Path.GetTempPath(),
                    $".xdm-update-{transaction.TransactionId}.json")
            };
            await WriteAsync(transactionPath, transaction, cancellationToken).ConfigureAwait(false);
            if (!string.IsNullOrWhiteSpace(transaction.RecoveryMarkerPath))
            {
                await WriteAsync(transaction.RecoveryMarkerPath, transaction, cancellationToken).ConfigureAwait(false);
            }

            DeleteDirectoryIfPresent(transaction.BackupPath);
            Directory.Move(transaction.InstallRoot, transaction.BackupPath);
            installationMovedToBackup = true;
            try
            {
                Directory.Move(transaction.CandidatePath, transaction.InstallRoot);
            }
            catch
            {
                if (!Directory.Exists(transaction.InstallRoot) && Directory.Exists(transaction.BackupPath))
                {
                    Directory.Move(transaction.BackupPath, transaction.InstallRoot);
                    installationMovedToBackup = false;
                }
                throw;
            }

            DateTimeOffset appliedAt = DateTimeOffset.UtcNow;
            transaction = transaction with
            {
                State = UpdateTransactionState.AppliedPendingHealth,
                UpdatedAtUtc = appliedAt,
                HealthyAfterUtc = appliedAt.Add(HealthObservationWindow)
            };
            await WriteAsync(transactionPath, transaction, cancellationToken).ConfigureAwait(false);
            if (!string.IsNullOrWhiteSpace(transaction.RecoveryMarkerPath))
            {
                await WriteAsync(transaction.RecoveryMarkerPath, transaction, cancellationToken).ConfigureAwait(false);
            }
            if (_launchApplication)
            {
                await MonitorNewApplicationAsync(transactionPath, transaction, cancellationToken).ConfigureAwait(false);
            }
        }
        catch (Exception exception) when (IsRecoverableApplyFailure(exception))
        {
            string failureMessage = exception.Message;
            string? restoreFailure = null;
            bool restored = !installationMovedToBackup
                || TryRestorePreviousInstallation(transaction, out restoreFailure);
            if (!restored)
            {
                failureMessage = string.Concat(failureMessage, " Rollback restoration also failed: ", restoreFailure);
            }
            else if (_launchApplication
                && installationMovedToBackup
                && !TryLaunchApplication(transaction, out string? launchFailure))
            {
                failureMessage = string.Concat(failureMessage, " The restored version could not be restarted: ", launchFailure);
            }
            UpdateTransactionDocument failed = transaction with
            {
                State = UpdateTransactionState.Failed,
                UpdatedAtUtc = DateTimeOffset.UtcNow,
                FailureMessage = failureMessage
            };
            await WriteAsync(transactionPath, failed, CancellationToken.None).ConfigureAwait(false);
            if (!string.IsNullOrWhiteSpace(failed.RecoveryMarkerPath))
            {
                await WriteAsync(failed.RecoveryMarkerPath, failed, CancellationToken.None).ConfigureAwait(false);
            }
            throw;
        }
        finally
        {
            DeleteDirectoryIfPresent(extractingPath);
        }
    }

    public async Task RollbackAsync(string transactionPath, CancellationToken cancellationToken = default)
    {
        UpdateTransactionDocument transaction = await ReadAsync(transactionPath, cancellationToken)
            .ConfigureAwait(false);
        await RecoverInterruptedTransactionAsync(transactionPath, transaction, cancellationToken)
            .ConfigureAwait(false);
        transaction = await ReadAsync(transactionPath, cancellationToken).ConfigureAwait(false);
        ValidateTransactionPaths(transaction, requireInstallRoot: false);
        if (!Directory.Exists(transaction.BackupPath))
        {
            throw new DirectoryNotFoundException("The rollback backup is missing.");
        }

        transaction = transaction with
        {
            State = UpdateTransactionState.RollingBack,
            UpdatedAtUtc = DateTimeOffset.UtcNow
        };
        await WriteAsync(transactionPath, transaction, cancellationToken).ConfigureAwait(false);
        string failedPath = $"{transaction.CandidatePath}.failed-{DateTimeOffset.UtcNow:yyyyMMddHHmmssfff}";
        if (Directory.Exists(transaction.InstallRoot))
        {
            Directory.Move(transaction.InstallRoot, failedPath);
        }
        try
        {
            Directory.Move(transaction.BackupPath, transaction.InstallRoot);
        }
        catch
        {
            if (!Directory.Exists(transaction.InstallRoot) && Directory.Exists(failedPath))
            {
                Directory.Move(failedPath, transaction.InstallRoot);
            }
            throw;
        }

        transaction = transaction with
        {
            State = UpdateTransactionState.RolledBack,
            UpdatedAtUtc = DateTimeOffset.UtcNow,
            FailureMessage = null
        };
        await WriteAsync(transactionPath, transaction, cancellationToken).ConfigureAwait(false);
        if (!string.IsNullOrWhiteSpace(transaction.RecoveryMarkerPath))
        {
            DeleteFileIfPresent(transaction.RecoveryMarkerPath);
        }
        if (_launchApplication)
        {
            LaunchApplication(transaction).Dispose();
        }
    }

    public static async Task MarkHealthyAsync(string transactionPath, CancellationToken cancellationToken = default)
    {
        UpdateTransactionDocument transaction = await ReadAsync(transactionPath, cancellationToken)
            .ConfigureAwait(false);
        if (transaction.State != UpdateTransactionState.AppliedPendingHealth)
        {
            return;
        }
        if (transaction.HealthyAfterUtc is DateTimeOffset readyAt)
        {
            TimeSpan remaining = readyAt - DateTimeOffset.UtcNow;
            if (remaining > TimeSpan.Zero)
            {
                await Task.Delay(remaining, cancellationToken).ConfigureAwait(false);
            }
        }
        UpdateTransactionDocument latest = await ReadAsync(transactionPath, cancellationToken)
            .ConfigureAwait(false);
        if (latest.State != UpdateTransactionState.AppliedPendingHealth
            || !string.Equals(latest.TransactionId, transaction.TransactionId, StringComparison.Ordinal))
        {
            return;
        }
        latest = latest with
        {
            State = UpdateTransactionState.Healthy,
            UpdatedAtUtc = DateTimeOffset.UtcNow,
            FailureMessage = null
        };
        await WriteAsync(transactionPath, latest, cancellationToken).ConfigureAwait(false);
        DeleteDirectoryIfPresent(latest.BackupPath);
        DeleteDirectoryIfPresent(latest.CandidatePath);
        if (!string.IsNullOrWhiteSpace(latest.RecoveryMarkerPath))
        {
            DeleteFileIfPresent(latest.RecoveryMarkerPath);
        }
    }

    private static async Task RecoverInterruptedTransactionAsync(
        string transactionPath,
        UpdateTransactionDocument transaction,
        CancellationToken cancellationToken)
    {
        if (transaction.State is not (UpdateTransactionState.Applying or UpdateTransactionState.AppliedPendingHealth or UpdateTransactionState.RollingBack))
        {
            return;
        }
        if (Directory.Exists(transaction.InstallRoot))
        {
            return;
        }
        if (!Directory.Exists(transaction.BackupPath))
        {
            return;
        }

        Directory.Move(transaction.BackupPath, transaction.InstallRoot);
        UpdateTransactionDocument recovered = transaction with
        {
            State = UpdateTransactionState.Failed,
            UpdatedAtUtc = DateTimeOffset.UtcNow,
            FailureMessage = "An interrupted update left the install root absent; the previous installation was restored before continuing."
        };
        await WriteAsync(transactionPath, recovered, cancellationToken).ConfigureAwait(false);
        if (!string.IsNullOrWhiteSpace(recovered.RecoveryMarkerPath))
        {
            await WriteAsync(recovered.RecoveryMarkerPath, recovered, cancellationToken).ConfigureAwait(false);
        }
        throw new InvalidOperationException("A previous update apply was interrupted and the previous installation was restored. Re-stage the update before applying again.");
    }

    private static bool IsRecoverableApplyFailure(Exception exception)
    {
        return exception is IOException
            or InvalidDataException
            or UnauthorizedAccessException
            or InvalidOperationException
            or Win32Exception
            or TimeoutException;
    }

    private static bool TryLaunchApplication(
        UpdateTransactionDocument transaction,
        out string? failureMessage)
    {
        try
        {
            LaunchApplication(transaction).Dispose();
            failureMessage = null;
            return true;
        }
        catch (Exception exception) when (IsRecoverableApplyFailure(exception))
        {
            failureMessage = exception.Message;
            return false;
        }
    }

    private static bool TryRestorePreviousInstallation(
        UpdateTransactionDocument transaction,
        out string? failureMessage)
    {
        failureMessage = null;
        if (!Directory.Exists(transaction.BackupPath))
        {
            return true;
        }

        try
        {
            if (Directory.Exists(transaction.InstallRoot))
            {
                string failedPath = $"{transaction.CandidatePath}.failed-{DateTimeOffset.UtcNow:yyyyMMddHHmmssfff}";
                Directory.Move(transaction.InstallRoot, failedPath);
            }
            Directory.Move(transaction.BackupPath, transaction.InstallRoot);
            return true;
        }
        catch (Exception exception) when (exception is IOException or UnauthorizedAccessException)
        {
            failureMessage = exception.Message;
            return false;
        }
    }

    private static async Task MonitorNewApplicationAsync(
        string transactionPath,
        UpdateTransactionDocument transaction,
        CancellationToken cancellationToken)
    {
        using Process process = LaunchApplication(transaction);
        DateTimeOffset deadline = (transaction.HealthyAfterUtc ?? DateTimeOffset.UtcNow.Add(HealthObservationWindow))
            .Add(PostObservationGraceWindow);
        while (DateTimeOffset.UtcNow < deadline)
        {
            await Task.Delay(TimeSpan.FromMilliseconds(500), cancellationToken).ConfigureAwait(false);
            UpdateTransactionDocument current = await ReadAsync(transactionPath, cancellationToken)
                .ConfigureAwait(false);
            if (current.State == UpdateTransactionState.Healthy)
            {
                if (!string.IsNullOrWhiteSpace(current.RecoveryMarkerPath))
                {
                    DeleteFileIfPresent(current.RecoveryMarkerPath);
                }
                return;
            }
            if (process.HasExited)
            {
                UpdateTransactionExecutor executor = new();
                await executor.RollbackAsync(transactionPath, cancellationToken).ConfigureAwait(false);
                return;
            }
        }

        UpdateTransactionDocument timedOut = await ReadAsync(transactionPath, cancellationToken)
            .ConfigureAwait(false);
        if (timedOut.State == UpdateTransactionState.Healthy)
        {
            return;
        }
        if (!process.HasExited)
        {
            try
            {
                process.Kill(entireProcessTree: true);
            }
            catch (InvalidOperationException)
            {
            }
            try
            {
                await process.WaitForExitAsync(cancellationToken).ConfigureAwait(false);
            }
            catch (InvalidOperationException)
            {
            }
        }
        UpdateTransactionExecutor rollbackExecutor = new();
        await rollbackExecutor.RollbackAsync(transactionPath, cancellationToken).ConfigureAwait(false);
    }

    private static Process LaunchApplication(UpdateTransactionDocument transaction)
    {
        string executable = transaction.ExecutableRelativePath
            ?? (OperatingSystem.IsWindows() ? "XDM.exe" : "XDM");
        string executablePath = Path.Combine(transaction.InstallRoot, executable);
        if (!File.Exists(executablePath))
        {
            throw new FileNotFoundException("The updated XDM executable is missing.", executablePath);
        }
        if (!OperatingSystem.IsWindows())
        {
            File.SetUnixFileMode(
                executablePath,
                UnixFileMode.UserRead | UnixFileMode.UserWrite | UnixFileMode.UserExecute
                | UnixFileMode.GroupRead | UnixFileMode.GroupExecute
                | UnixFileMode.OtherRead | UnixFileMode.OtherExecute);
        }
        ProcessStartInfo start = new()
        {
            FileName = executablePath,
            WorkingDirectory = transaction.InstallRoot,
            UseShellExecute = true
        };
        return Process.Start(start)
            ?? throw new InvalidOperationException("The updated XDM process could not be started.");
    }

    private static async Task VerifyPackageAsync(
        UpdateTransactionDocument transaction,
        CancellationToken cancellationToken)
    {
        FileInfo package = new(transaction.PackagePath);
        if (!package.Exists || package.Length != transaction.PackageSizeBytes)
        {
            throw new InvalidDataException("The staged update package size changed after verification.");
        }
        await using FileStream stream = new(
            transaction.PackagePath,
            FileMode.Open,
            FileAccess.Read,
            FileShare.Read,
            64 * 1024,
            FileOptions.Asynchronous | FileOptions.SequentialScan);
        byte[] digest = await SHA256.HashDataAsync(stream, cancellationToken).ConfigureAwait(false);
        string actual = Convert.ToHexString(digest);
        if (!string.Equals(actual, transaction.PackageSha256, StringComparison.OrdinalIgnoreCase))
        {
            throw new InvalidDataException("The staged update package changed after verification.");
        }
    }

    private static void ExtractPortableZip(string packagePath, string destination)
    {
        if (!packagePath.EndsWith(".zip", StringComparison.OrdinalIgnoreCase))
        {
            throw new InvalidDataException("Automatic application requires a portable ZIP package.");
        }
        using ZipArchive archive = ZipFile.OpenRead(packagePath);
        if (archive.Entries.Count > MaximumEntries)
        {
            throw new InvalidDataException("The update archive contains too many entries.");
        }
        string destinationRoot = Path.GetFullPath(destination) + Path.DirectorySeparatorChar;
        long expanded = 0;
        foreach (ZipArchiveEntry entry in archive.Entries)
        {
            expanded = checked(expanded + entry.Length);
            if (expanded > MaximumExpandedBytes)
            {
                throw new InvalidDataException("The update archive expands beyond the allowed size.");
            }
            if (IsSymbolicLink(entry))
            {
                throw new InvalidDataException("Symbolic links are not allowed in update archives.");
            }
            string fullPath = Path.GetFullPath(Path.Combine(destination, entry.FullName));
            if (!fullPath.StartsWith(destinationRoot, PathComparison))
            {
                throw new InvalidDataException("The update archive contains an unsafe path.");
            }
            if (entry.FullName.EndsWith('/'))
            {
                Directory.CreateDirectory(fullPath);
                continue;
            }
            Directory.CreateDirectory(Path.GetDirectoryName(fullPath)!);
            entry.ExtractToFile(fullPath, overwrite: true);
        }
    }

    private static bool IsSymbolicLink(ZipArchiveEntry entry)
    {
        int unixMode = (entry.ExternalAttributes >> 16) & 0xF000;
        return unixMode == 0xA000;
    }

    private static void EnsureUnixExecutables(string candidate, string? executableRelativePath)
    {
        if (OperatingSystem.IsWindows())
        {
            return;
        }

        UnixFileMode executableMode =
            UnixFileMode.UserRead | UnixFileMode.UserWrite | UnixFileMode.UserExecute
            | UnixFileMode.GroupRead | UnixFileMode.GroupExecute
            | UnixFileMode.OtherRead | UnixFileMode.OtherExecute;
        string application = executableRelativePath ?? "XDM";
        foreach (string relativePath in new[] { application, "XDM.NativeHost", "XDM.Updater" })
        {
            string path = Path.Combine(candidate, relativePath);
            if (File.Exists(path))
            {
                File.SetUnixFileMode(path, executableMode);
            }
        }
    }

    private static void ValidateCandidate(string candidate, string? executableRelativePath)
    {
        string executable = executableRelativePath
            ?? (OperatingSystem.IsWindows() ? "XDM.exe" : "XDM");
        if (!File.Exists(Path.Combine(candidate, executable)))
        {
            throw new InvalidDataException("The portable update does not contain the XDM executable.");
        }
        string host = OperatingSystem.IsWindows() ? "XDM.NativeHost.exe" : "XDM.NativeHost";
        if (!File.Exists(Path.Combine(candidate, host)))
        {
            throw new InvalidDataException("The portable update does not contain the browser native host.");
        }
        string updater = OperatingSystem.IsWindows() ? "XDM.Updater.exe" : "XDM.Updater";
        if (!File.Exists(Path.Combine(candidate, updater)))
        {
            throw new InvalidDataException("The portable update does not contain the external updater helper.");
        }
    }

    private static void ValidateTransactionPaths(UpdateTransactionDocument transaction, bool requireInstallRoot = true)
    {
        if (transaction.SchemaVersion is not (1 or 2)
            || string.IsNullOrWhiteSpace(transaction.TransactionId)
            || string.IsNullOrWhiteSpace(transaction.TargetVersion)
            || string.IsNullOrWhiteSpace(transaction.PackagePath)
            || string.IsNullOrWhiteSpace(transaction.InstallRoot)
            || string.IsNullOrWhiteSpace(transaction.BackupPath)
            || string.IsNullOrWhiteSpace(transaction.CandidatePath)
            || transaction.PackageSizeBytes is <= 0 or > MaximumPackageBytes
            || string.IsNullOrWhiteSpace(transaction.PackageSha256)
            || transaction.PackageSha256.Length != 64
            || !transaction.PackageSha256.All(Uri.IsHexDigit)
            || !Enum.IsDefined(transaction.Channel))
        {
            throw new InvalidDataException("The update transaction metadata is invalid.");
        }

        string install = Path.TrimEndingDirectorySeparator(Path.GetFullPath(transaction.InstallRoot));
        string root = Path.GetPathRoot(install) ?? string.Empty;
        if (install.Length <= root.Length || (requireInstallRoot && !Directory.Exists(install)))
        {
            throw new InvalidDataException("The transaction installation path is unsafe or missing.");
        }
        string parent = Directory.GetParent(install)?.FullName
            ?? throw new InvalidDataException("The installation directory has no parent.");
        string backup = Path.TrimEndingDirectorySeparator(Path.GetFullPath(transaction.BackupPath));
        string candidate = Path.TrimEndingDirectorySeparator(Path.GetFullPath(transaction.CandidatePath));
        if (string.Equals(backup, candidate, PathComparison)
            || string.Equals(backup, install, PathComparison)
            || string.Equals(candidate, install, PathComparison))
        {
            throw new InvalidDataException("The update transaction paths must be distinct.");
        }

        foreach (string full in new[] { backup, candidate })
        {
            if (!string.Equals(Directory.GetParent(full)?.FullName, parent, PathComparison))
            {
                throw new InvalidDataException("Rollback and candidate paths must be siblings of the installation directory.");
            }
        }
        if (!string.IsNullOrWhiteSpace(transaction.RecoveryMarkerPath))
        {
            string recoveryParent = Directory.GetParent(Path.GetFullPath(transaction.RecoveryMarkerPath))?.FullName ?? string.Empty;
            if (!string.Equals(recoveryParent, parent, PathComparison))
            {
                throw new InvalidDataException("The update recovery marker must be beside the installation directory.");
            }
        }
    }

    private static async Task WaitForProcessExitAsync(
        int processId,
        TimeSpan timeout,
        CancellationToken cancellationToken)
    {
        try
        {
            using Process process = Process.GetProcessById(processId);
            DateTimeOffset deadline = DateTimeOffset.UtcNow.Add(timeout);
            while (!process.HasExited)
            {
                if (DateTimeOffset.UtcNow >= deadline)
                {
                    throw new TimeoutException("The previous XDM process did not exit before the updater deadline.");
                }
                TimeSpan remaining = deadline - DateTimeOffset.UtcNow;
                TimeSpan delay = remaining < TimeSpan.FromMilliseconds(250)
                    ? remaining
                    : TimeSpan.FromMilliseconds(250);
                await Task.Delay(delay, cancellationToken).ConfigureAwait(false);
            }
        }
        catch (ArgumentException)
        {
        }
    }

    private static async Task<UpdateTransactionDocument> ReadAsync(
        string path,
        CancellationToken cancellationToken)
    {
        await using FileStream stream = File.OpenRead(path);
        return await JsonSerializer.DeserializeAsync<UpdateTransactionDocument>(
            stream,
            JsonOptions,
            cancellationToken).ConfigureAwait(false)
            ?? throw new InvalidDataException("The update transaction is empty.");
    }

    private static async Task WriteAsync(
        string path,
        UpdateTransactionDocument transaction,
        CancellationToken cancellationToken)
    {
        string temporary = $"{path}.tmp";
        Directory.CreateDirectory(Path.GetDirectoryName(path)!);
        await using (FileStream stream = new(
            temporary,
            FileMode.Create,
            FileAccess.Write,
            FileShare.None,
            32 * 1024,
            FileOptions.Asynchronous | FileOptions.WriteThrough))
        {
            await JsonSerializer.SerializeAsync(stream, transaction, JsonOptions, cancellationToken)
                .ConfigureAwait(false);
            await stream.FlushAsync(cancellationToken).ConfigureAwait(false);
            stream.Flush(flushToDisk: true);
        }
        File.Move(temporary, path, overwrite: true);
    }

    private static void DeleteDirectoryIfPresent(string path)
    {
        if (Directory.Exists(path))
        {
            Directory.Delete(path, recursive: true);
        }
    }

    private static void DeleteFileIfPresent(string path)
    {
        if (File.Exists(path))
        {
            File.Delete(path);
        }
    }
}
