using System.Diagnostics;
using System.Globalization;
using System.Security.Cryptography;
using System.Text.Json;
using XDM.Core.Abstractions;
using XDM.Core.Product;

namespace XDM.Platform;

public sealed class VerifiedUpdateService : IUpdateService
{
    private const long MaximumPackageBytes = 2L * 1024 * 1024 * 1024;
    private const int MaximumManifestBytes = 1024 * 1024;
    private const int MaximumRetainedUpdateVersions = 3;
    private static readonly TimeSpan UpdateArtifactRetention = TimeSpan.FromDays(14);
    private static readonly HashSet<string> AllowedPackageExtensions = new(
        [".zip", ".msi", ".exe", ".deb", ".rpm", ".appimage", ".gz"],
        StringComparer.OrdinalIgnoreCase);
    private static readonly HashSet<string> PortableSelfUpdateExtensions = new(
        [".zip"],
        StringComparer.OrdinalIgnoreCase);
    private static readonly HashSet<string> AllowedHosts = new(
        ["github.com", "objects.githubusercontent.com", "release-assets.githubusercontent.com", "raw.githubusercontent.com"],
        StringComparer.OrdinalIgnoreCase);
    private static readonly JsonSerializerOptions ManifestJsonOptions = new(JsonSerializerDefaults.Web)
    {
        WriteIndented = true
    };

    private readonly HttpClient _httpClient;
    private readonly IPlatformInfo _platformInfo;
    private readonly string _updateRoot;
    private readonly Uri? _manifestUriOverride;

    public VerifiedUpdateService(HttpClient httpClient, IPlatformInfo platformInfo)
        : this(
            httpClient,
            platformInfo,
            Path.Combine(
                Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
                "XDM",
                "updates"),
            manifestUri: null)
    {
    }

    internal VerifiedUpdateService(
        HttpClient httpClient,
        IPlatformInfo platformInfo,
        string updateRoot,
        Uri? manifestUri)
    {
        _httpClient = httpClient;
        _platformInfo = platformInfo;
        _updateRoot = Path.GetFullPath(updateRoot);
        _manifestUriOverride = manifestUri;
    }

    public Task<UpdateCheckResult> CheckAsync(CancellationToken cancellationToken = default)
        => CheckAsync(UpdateChannel.Stable, cancellationToken);

    public async Task<UpdateCheckResult> CheckAsync(
        UpdateChannel channel,
        CancellationToken cancellationToken = default)
    {
        Uri manifestUri = _manifestUriOverride ?? ModernFeaturePolicy.GetUpdateManifest(channel);
        ValidateHttpsUri(manifestUri, nameof(manifestUri));
        using HttpResponseMessage response = await _httpClient.GetAsync(
            manifestUri,
            HttpCompletionOption.ResponseHeadersRead,
            cancellationToken).ConfigureAwait(false);
        response.EnsureSuccessStatusCode();
        ValidateTrustedHttpsHost(response.RequestMessage?.RequestUri ?? manifestUri);
        if (response.Content.Headers.ContentLength is > MaximumManifestBytes)
        {
            throw new InvalidDataException("The update manifest exceeds 1 MiB.");
        }

        byte[] manifestBytes = await ReadBoundedAsync(
            response.Content,
            MaximumManifestBytes,
            cancellationToken).ConfigureAwait(false);
        UpdateManifestDocument manifest = JsonSerializer.Deserialize<UpdateManifestDocument>(
            manifestBytes,
            ManifestJsonOptions)
            ?? throw new InvalidDataException("The update manifest is empty.");
        ValidateManifest(manifest, channel);

        string runtimeIdentifier = ResolveRuntimeIdentifier(_platformInfo);
        UpdatePackageDescriptor? package = manifest.Packages!
            .Where(IsPortableSelfUpdatePackage)
            .FirstOrDefault(item => string.Equals(
                item.RuntimeIdentifier,
                runtimeIdentifier,
                StringComparison.OrdinalIgnoreCase));
        bool managedPackageAvailable = manifest.Packages!.Any(item =>
            string.Equals(item.RuntimeIdentifier, runtimeIdentifier, StringComparison.OrdinalIgnoreCase)
            && !IsPortableSelfUpdatePackage(item));
        bool updateAvailable = SemanticVersion.IsUpdateAvailable(
            manifest.Version,
            ProductVersion.Current,
            channel);
        bool mandatory = updateAvailable
            && !string.IsNullOrWhiteSpace(manifest.MinimumSupportedVersion)
            && SemanticVersion.Compare(ProductVersion.Current, manifest.MinimumSupportedVersion) < 0;
        Uri? releaseNotes = TryCreateHttpsUri(manifest.ReleaseNotesUrl);
        string channelName = channel.ToManifestName();
        string message = updateAvailable
            ? package is null
                ? managedPackageAvailable
                    ? $"XDM {manifest.Version} is available on {channelName} for {runtimeIdentifier}; update through the OS package manager for this installation."
                    : $"XDM {manifest.Version} is available on {channelName}, but this platform has no portable self-update package."
                : mandatory
                    ? $"XDM {manifest.Version} is required because this installation is below {manifest.MinimumSupportedVersion}. Download and apply the verified update before continuing optional update deferrals."
                    : $"XDM {manifest.Version} is available on {channelName} for {runtimeIdentifier}."
            : $"XDM {ProductVersion.Current} is current on {channelName}.";
        return new UpdateCheckResult(
            ProductVersion.Current,
            manifest.Version,
            updateAvailable,
            releaseNotes,
            package,
            message,
            channel,
            mandatory,
            manifest.PublishedAtUtc,
            manifest.ReleaseCommitSha,
            manifest.ReleaseTag);
    }

    public async Task<StagedUpdateResult> StageAsync(
        UpdateCheckResult update,
        IProgress<double>? progress = null,
        CancellationToken cancellationToken = default)
    {
        ArgumentNullException.ThrowIfNull(update);
        UpdatePackageDescriptor package = update.Package
            ?? throw new InvalidOperationException("No compatible portable update package is available for this runtime.");
        ValidatePackage(package);
        if (!IsPortableSelfUpdatePackage(package))
        {
            throw new InvalidOperationException("Automatic application requires a portable ZIP package. Package-manager artifacts must be installed through the OS package manager.");
        }
        Uri packageUri = new(package.Url, UriKind.Absolute);
        ValidatePackageUri(packageUri);

        string channelDirectory = Path.Combine(_updateRoot, update.Channel.ToManifestName());
        string versionDirectory = Path.Combine(channelDirectory, SanitizeSegment(update.AvailableVersion));
        Directory.CreateDirectory(versionDirectory);
        string destinationPath = Path.Combine(versionDirectory, SanitizeFileName(package.FileName));
        string temporaryPath = $"{destinationPath}.downloading";
        try
        {
            using HttpResponseMessage response = await _httpClient.GetAsync(
                packageUri,
                HttpCompletionOption.ResponseHeadersRead,
                cancellationToken).ConfigureAwait(false);
            response.EnsureSuccessStatusCode();
            ValidateTrustedHttpsHost(response.RequestMessage?.RequestUri ?? packageUri);
            if (response.Content.Headers.ContentLength is long contentLength
                && contentLength != package.SizeBytes)
            {
                throw new InvalidDataException("The update package length does not match the published manifest.");
            }

            await using Stream source = await response.Content.ReadAsStreamAsync(cancellationToken)
                .ConfigureAwait(false);
            using IncrementalHash sha256 = IncrementalHash.CreateHash(HashAlgorithmName.SHA256);
            using IncrementalHash sha512 = IncrementalHash.CreateHash(HashAlgorithmName.SHA512);
            byte[] buffer = new byte[64 * 1024];
            long downloaded = 0;
            await using (FileStream destination = new(
                temporaryPath,
                FileMode.Create,
                FileAccess.Write,
                FileShare.None,
                64 * 1024,
                FileOptions.Asynchronous | FileOptions.SequentialScan | FileOptions.WriteThrough))
            {
                while (true)
                {
                    int read = await source.ReadAsync(buffer, cancellationToken).ConfigureAwait(false);
                    if (read == 0)
                    {
                        break;
                    }

                    downloaded += read;
                    if (downloaded > package.SizeBytes || downloaded > MaximumPackageBytes)
                    {
                        throw new InvalidDataException("The update package exceeded its declared size.");
                    }

                    sha256.AppendData(buffer, 0, read);
                    sha512.AppendData(buffer, 0, read);
                    await destination.WriteAsync(buffer.AsMemory(0, read), cancellationToken).ConfigureAwait(false);
                    progress?.Report((double)downloaded / package.SizeBytes);
                }

                await destination.FlushAsync(cancellationToken).ConfigureAwait(false);
                destination.Flush(flushToDisk: true);
            }

            if (downloaded != package.SizeBytes)
            {
                throw new InvalidDataException("The update package is incomplete.");
            }

            string actualSha256 = Convert.ToHexString(sha256.GetHashAndReset());
            string actualSha512 = Convert.ToHexString(sha512.GetHashAndReset());
            if (!string.Equals(actualSha256, package.Sha256, StringComparison.OrdinalIgnoreCase))
            {
                throw new InvalidDataException("The update package SHA-256 does not match the manifest.");
            }
            if (!string.IsNullOrWhiteSpace(package.Sha512)
                && !string.Equals(actualSha512, package.Sha512, StringComparison.OrdinalIgnoreCase))
            {
                throw new InvalidDataException("The update package SHA-512 does not match the manifest.");
            }

            File.Move(temporaryPath, destinationPath, overwrite: true);
            string receiptPath = Path.Combine(versionDirectory, "verification-receipt.json");
            UpdateVerificationReceipt receipt = new(
                2,
                update.AvailableVersion,
                update.Channel,
                package.RuntimeIdentifier,
                package.FileName,
                actualSha256,
                actualSha512,
                downloaded,
                DateTimeOffset.UtcNow,
                package.SbomUrl,
                package.ProvenanceUrl,
                package.CommitSha ?? update.ReleaseCommitSha,
                update.ReleaseTag,
                package.SignatureUrl,
                package.SignatureSha256,
                package.InstallModel ?? "portable-self-update");
            await WriteAtomicJsonAsync(receiptPath, receipt, cancellationToken).ConfigureAwait(false);

            string transactionId = $"{DateTimeOffset.UtcNow:yyyyMMddHHmmss}-{Guid.NewGuid():N}";
            string installRoot = Path.TrimEndingDirectorySeparator(Path.GetFullPath(AppContext.BaseDirectory));
            string installParent = Directory.GetParent(installRoot)?.FullName
                ?? throw new InvalidOperationException("The installation directory has no writable parent.");
            string transactionPath = Path.Combine(versionDirectory, "update-transaction.json");
            UpdateTransactionDocument transaction = new(
                2,
                transactionId,
                ProductVersion.Current,
                update.AvailableVersion,
                update.Channel,
                package.RuntimeIdentifier,
                destinationPath,
                actualSha256,
                downloaded,
                installRoot,
                Path.Combine(installParent, $".xdm-rollback-{transactionId}"),
                Path.Combine(installParent, $".xdm-candidate-{transactionId}"),
                UpdateTransactionState.Staged,
                DateTimeOffset.UtcNow,
                DateTimeOffset.UtcNow,
                ExecutableRelativePath: OperatingSystem.IsWindows() ? "XDM.exe" : "XDM",
                InstallModel: package.InstallModel ?? "portable-self-update",
                RecoveryMarkerPath: Path.Combine(installParent, $".xdm-update-{transactionId}.json"));
            await WriteAtomicJsonAsync(transactionPath, transaction, cancellationToken).ConfigureAwait(false);
            PruneStagingTrees(channelDirectory, versionDirectory);

            return new StagedUpdateResult(
                update.AvailableVersion,
                destinationPath,
                actualSha256,
                downloaded,
                actualSha512,
                receiptPath,
                transactionPath);
        }
        finally
        {
            if (File.Exists(temporaryPath))
            {
                File.Delete(temporaryPath);
            }
        }
    }

    public Task LaunchStagedUpdateAsync(
        StagedUpdateResult stagedUpdate,
        int currentProcessId,
        CancellationToken cancellationToken = default)
    {
        ArgumentNullException.ThrowIfNull(stagedUpdate);
        cancellationToken.ThrowIfCancellationRequested();
        ArgumentOutOfRangeException.ThrowIfNegativeOrZero(currentProcessId);
        if (!stagedUpdate.PackagePath.EndsWith(".zip", StringComparison.OrdinalIgnoreCase))
        {
            throw new InvalidOperationException("Automatic application requires a portable ZIP package.");
        }
        if (string.IsNullOrWhiteSpace(stagedUpdate.TransactionPath)
            || !File.Exists(stagedUpdate.TransactionPath))
        {
            throw new FileNotFoundException("The staged update transaction is missing.", stagedUpdate.TransactionPath);
        }
        if (IsPackageManagedInstallRoot(AppContext.BaseDirectory))
        {
            throw new InvalidOperationException("This XDM installation is package-managed. Apply .deb/.rpm updates through the OS package manager instead of mutating the installation directory with portable self-update.");
        }

        string updaterName = OperatingSystem.IsWindows() ? "XDM.Updater.exe" : "XDM.Updater";
        string bundledUpdater = Path.Combine(AppContext.BaseDirectory, updaterName);
        if (!File.Exists(bundledUpdater))
        {
            throw new FileNotFoundException("The packaged XDM updater helper is missing.", bundledUpdater);
        }

        string runnerDirectory = Path.Combine(
            Path.GetDirectoryName(stagedUpdate.TransactionPath)!,
            "runner");
        Directory.CreateDirectory(runnerDirectory);
        string externalRunner = Path.Combine(runnerDirectory, updaterName);
        File.Copy(bundledUpdater, externalRunner, overwrite: true);
        if (!OperatingSystem.IsWindows())
        {
            File.SetUnixFileMode(
                externalRunner,
                UnixFileMode.UserRead | UnixFileMode.UserWrite | UnixFileMode.UserExecute
                | UnixFileMode.GroupRead | UnixFileMode.GroupExecute
                | UnixFileMode.OtherRead | UnixFileMode.OtherExecute);
        }

        ProcessStartInfo startInfo = new()
        {
            FileName = externalRunner,
            UseShellExecute = false,
            WorkingDirectory = runnerDirectory
        };
        startInfo.ArgumentList.Add("--apply");
        startInfo.ArgumentList.Add(stagedUpdate.TransactionPath);
        startInfo.ArgumentList.Add("--wait-pid");
        startInfo.ArgumentList.Add(currentProcessId.ToString(CultureInfo.InvariantCulture));
        startInfo.ArgumentList.Add("--external-runner");
        using Process process = Process.Start(startInfo)
            ?? throw new InvalidOperationException("The external updater process could not be started.");
        return Task.CompletedTask;
    }

    public async Task MarkCurrentVersionHealthyAsync(CancellationToken cancellationToken = default)
    {
        if (!Directory.Exists(_updateRoot))
        {
            return;
        }

        string[] transactions = Directory.GetFiles(
            _updateRoot,
            "update-transaction.json",
            SearchOption.AllDirectories);
        foreach (string transactionPath in transactions.OrderByDescending(File.GetLastWriteTimeUtc))
        {
            cancellationToken.ThrowIfCancellationRequested();
            UpdateTransactionDocument? transaction = await TryReadTransactionAsync(
                transactionPath,
                cancellationToken).ConfigureAwait(false);
            if (transaction is null
                || transaction.State != UpdateTransactionState.AppliedPendingHealth
                || !string.Equals(transaction.TargetVersion, ProductVersion.Current, StringComparison.OrdinalIgnoreCase))
            {
                continue;
            }

            if (transaction.HealthyAfterUtc is DateTimeOffset readyAt)
            {
                TimeSpan remaining = readyAt - DateTimeOffset.UtcNow;
                if (remaining > TimeSpan.Zero)
                {
                    await Task.Delay(remaining, cancellationToken).ConfigureAwait(false);
                }
            }

            UpdateTransactionDocument? latest = await TryReadTransactionAsync(
                transactionPath,
                cancellationToken).ConfigureAwait(false);
            if (latest is null
                || latest.State != UpdateTransactionState.AppliedPendingHealth
                || !string.Equals(latest.TransactionId, transaction.TransactionId, StringComparison.Ordinal)
                || !string.Equals(latest.TargetVersion, ProductVersion.Current, StringComparison.OrdinalIgnoreCase))
            {
                continue;
            }

            UpdateTransactionDocument healthy = latest with
            {
                State = UpdateTransactionState.Healthy,
                UpdatedAtUtc = DateTimeOffset.UtcNow,
                FailureMessage = null
            };
            await WriteAtomicJsonAsync(transactionPath, healthy, cancellationToken).ConfigureAwait(false);
            TryDeleteDirectory(latest.BackupPath);
            TryDeleteDirectory(latest.CandidatePath);
            if (!string.IsNullOrWhiteSpace(latest.RecoveryMarkerPath))
            {
                TryDeleteFile(latest.RecoveryMarkerPath);
            }
            PruneStagingTrees(Path.GetDirectoryName(Path.GetDirectoryName(transactionPath)!)!, Path.GetDirectoryName(transactionPath)!);
            return;
        }
    }

    private static async Task<UpdateTransactionDocument?> TryReadTransactionAsync(
        string transactionPath,
        CancellationToken cancellationToken)
    {
        try
        {
            await using FileStream source = File.OpenRead(transactionPath);
            return await JsonSerializer.DeserializeAsync<UpdateTransactionDocument>(
                source,
                ManifestJsonOptions,
                cancellationToken).ConfigureAwait(false);
        }
        catch (JsonException)
        {
            return null;
        }
        catch (IOException)
        {
            return null;
        }
        catch (UnauthorizedAccessException)
        {
            return null;
        }
    }

    private static void TryDeleteDirectory(string path)
    {
        try
        {
            if (Directory.Exists(path))
            {
                Directory.Delete(path, recursive: true);
            }
        }
        catch (IOException)
        {
        }
        catch (UnauthorizedAccessException)
        {
        }
    }

    private static void TryDeleteFile(string path)
    {
        try
        {
            if (File.Exists(path))
            {
                File.Delete(path);
            }
        }
        catch (IOException)
        {
        }
        catch (UnauthorizedAccessException)
        {
        }
    }

    private static void ValidateManifest(UpdateManifestDocument manifest, UpdateChannel requestedChannel)
    {
        if (manifest.SchemaVersion is not (1 or 2))
        {
            throw new InvalidDataException($"Unsupported update manifest schema {manifest.SchemaVersion}.");
        }
        if (!SemanticVersion.IsValid(manifest.Version))
        {
            throw new InvalidDataException("The update manifest version is invalid.");
        }
        if (manifest.SchemaVersion == 2)
        {
            if (!UpdateChannelExtensions.TryParse(manifest.Channel, out UpdateChannel manifestChannel)
                || manifestChannel != requestedChannel)
            {
                throw new InvalidDataException("The update manifest channel does not match the requested channel.");
            }
            if (manifest.PublishedAtUtc is null)
            {
                throw new InvalidDataException("The update manifest publication time is missing.");
            }
            if (!string.IsNullOrWhiteSpace(manifest.MinimumSupportedVersion)
                && !SemanticVersion.IsValid(manifest.MinimumSupportedVersion))
            {
                throw new InvalidDataException("The minimum supported version is invalid.");
            }
            if (!string.IsNullOrWhiteSpace(manifest.ReleaseCommitSha)
                && !IsHexDigest(manifest.ReleaseCommitSha, 40))
            {
                throw new InvalidDataException("The release commit SHA is invalid.");
            }
            if (!string.IsNullOrWhiteSpace(manifest.ReleaseTag)
                && manifest.ReleaseTag.IndexOfAny(Path.GetInvalidFileNameChars()) >= 0)
            {
                throw new InvalidDataException("The release tag is invalid.");
            }
        }
        else if (requestedChannel != UpdateChannel.Stable)
        {
            throw new InvalidDataException("Legacy update manifests are accepted only on the stable channel.");
        }
        if (manifest.Packages is null || manifest.Packages.Count == 0 || manifest.Packages.Count > 32)
        {
            throw new InvalidDataException("The update manifest package list is invalid.");
        }
        foreach (UpdatePackageDescriptor package in manifest.Packages)
        {
            ValidatePackage(package);
            if (!string.IsNullOrWhiteSpace(manifest.ReleaseCommitSha)
                && !string.Equals(package.CommitSha, manifest.ReleaseCommitSha, StringComparison.OrdinalIgnoreCase))
            {
                throw new InvalidDataException("The update package commit does not match the release manifest commit.");
            }
        }
    }

    private static void ValidatePackage(UpdatePackageDescriptor package)
    {
        if (string.IsNullOrWhiteSpace(package.RuntimeIdentifier)
            || string.IsNullOrWhiteSpace(package.Url)
            || string.IsNullOrWhiteSpace(package.Sha256)
            || string.IsNullOrWhiteSpace(package.FileName)
            || package.SizeBytes is <= 0 or > MaximumPackageBytes
            || !IsHexDigest(package.Sha256, 64)
            || (!string.IsNullOrWhiteSpace(package.Sha512) && !IsHexDigest(package.Sha512, 128))
            || (!string.IsNullOrWhiteSpace(package.CommitSha) && !IsHexDigest(package.CommitSha, 40))
            || (!string.IsNullOrWhiteSpace(package.SignatureSha256) && !IsHexDigest(package.SignatureSha256, 64))
            || !Uri.TryCreate(package.Url, UriKind.Absolute, out Uri? packageUri))
        {
            throw new InvalidDataException("The update package metadata is invalid.");
        }
        ValidatePackageUri(packageUri);
        ValidateOptionalTrustedUri(package.SbomUrl, "SBOM");
        ValidateOptionalTrustedUri(package.ProvenanceUrl, "provenance");
        ValidateOptionalTrustedUri(package.SignatureUrl, "signature");
        if (package.OfficialWindowsRelease
            && package.RuntimeIdentifier.StartsWith("win-", StringComparison.OrdinalIgnoreCase)
            && (string.IsNullOrWhiteSpace(package.CommitSha)
                || string.IsNullOrWhiteSpace(package.Sha512)
                || string.IsNullOrWhiteSpace(package.ProvenanceUrl)))
        {
            throw new InvalidDataException("Official Windows update packages must publish commit, SHA-512, and provenance metadata after Authenticode verification.");
        }
        string extension = Path.GetExtension(package.FileName);
        if (!AllowedPackageExtensions.Contains(extension)
            && !package.FileName.EndsWith(".tar.gz", StringComparison.OrdinalIgnoreCase))
        {
            throw new InvalidDataException("The update package file type is not allowed.");
        }
    }

    private static bool IsPortableSelfUpdatePackage(UpdatePackageDescriptor package)
        => PortableSelfUpdateExtensions.Contains(Path.GetExtension(package.FileName))
            && string.Equals(package.InstallModel ?? "portable-self-update", "portable-self-update", StringComparison.OrdinalIgnoreCase);

    private static bool IsHexDigest(string value, int expectedLength)
        => value.Length == expectedLength && value.All(Uri.IsHexDigit);

    private static void ValidateOptionalTrustedUri(string? value, string description)
    {
        if (string.IsNullOrWhiteSpace(value))
        {
            return;
        }
        if (!Uri.TryCreate(value, UriKind.Absolute, out Uri? uri))
        {
            throw new InvalidDataException($"The update {description} URL is invalid.");
        }
        ValidateTrustedHttpsHost(uri);
    }

    private static void ValidatePackageUri(Uri uri)
        => ValidateTrustedHttpsHost(uri);

    private static void ValidateTrustedHttpsHost(Uri uri)
    {
        ValidateHttpsUri(uri, nameof(uri));
        if (!AllowedHosts.Contains(uri.Host))
        {
            throw new InvalidDataException("The update host is not trusted.");
        }
    }

    private static void ValidateHttpsUri(Uri uri, string parameterName)
    {
        if (!uri.IsAbsoluteUri || uri.Scheme != Uri.UriSchemeHttps)
        {
            throw new ArgumentException("Update URLs must use HTTPS.", parameterName);
        }
    }

    private static Uri? TryCreateHttpsUri(string? value)
        => Uri.TryCreate(value, UriKind.Absolute, out Uri? uri) && uri.Scheme == Uri.UriSchemeHttps
            ? uri
            : null;

    private static async Task<byte[]> ReadBoundedAsync(
        HttpContent content,
        int maximumBytes,
        CancellationToken cancellationToken)
    {
        await using Stream stream = await content.ReadAsStreamAsync(cancellationToken).ConfigureAwait(false);
        using MemoryStream buffer = new();
        byte[] block = new byte[16 * 1024];
        while (true)
        {
            int read = await stream.ReadAsync(block, cancellationToken).ConfigureAwait(false);
            if (read == 0)
            {
                break;
            }
            if (buffer.Length + read > maximumBytes)
            {
                throw new InvalidDataException("The update manifest exceeds 1 MiB.");
            }
            await buffer.WriteAsync(block.AsMemory(0, read), cancellationToken).ConfigureAwait(false);
        }
        return buffer.ToArray();
    }

    private static async Task WriteAtomicJsonAsync<T>(
        string path,
        T value,
        CancellationToken cancellationToken)
    {
        string temporaryPath = $"{path}.tmp";
        Directory.CreateDirectory(Path.GetDirectoryName(path)!);
        try
        {
            await using (FileStream stream = new(
                temporaryPath,
                FileMode.Create,
                FileAccess.Write,
                FileShare.None,
                32 * 1024,
                FileOptions.Asynchronous | FileOptions.WriteThrough))
            {
                await JsonSerializer.SerializeAsync(stream, value, ManifestJsonOptions, cancellationToken)
                    .ConfigureAwait(false);
                await stream.FlushAsync(cancellationToken).ConfigureAwait(false);
                stream.Flush(flushToDisk: true);
            }
            File.Move(temporaryPath, path, overwrite: true);
        }
        finally
        {
            if (File.Exists(temporaryPath))
            {
                File.Delete(temporaryPath);
            }
        }
    }

    private static string ResolveRuntimeIdentifier(IPlatformInfo platform)
    {
        string os = platform.OperatingSystem.Contains("Windows", StringComparison.OrdinalIgnoreCase)
            ? "win"
            : platform.OperatingSystem.Contains("Linux", StringComparison.OrdinalIgnoreCase)
                ? "linux"
                : "unsupported";
        string architecture = platform.Architecture.ToLowerInvariant() switch
        {
            "x64" or "amd64" => "x64",
            "arm64" or "aarch64" => "arm64",
            _ => "unsupported"
        };
        return $"{os}-{architecture}";
    }

    private static string SanitizeFileName(string value)
    {
        string fileName = Path.GetFileName(value);
        if (string.IsNullOrWhiteSpace(fileName) || fileName != value)
        {
            throw new InvalidDataException("The update package filename is invalid.");
        }
        return fileName;
    }

    private static string SanitizeSegment(string value)
    {
        char[] invalid = Path.GetInvalidFileNameChars();
        string result = new(value.Select(character => Array.IndexOf(invalid, character) >= 0 ? '_' : character).ToArray());
        return string.IsNullOrWhiteSpace(result) ? "update" : result;
    }

    private static bool IsPackageManagedInstallRoot(string installRoot)
    {
        if (string.Equals(Environment.GetEnvironmentVariable("XDM_PACKAGE_MANAGED"), "1", StringComparison.Ordinal))
        {
            return true;
        }
        if (!OperatingSystem.IsLinux())
        {
            return false;
        }
        string normalized = Path.TrimEndingDirectorySeparator(Path.GetFullPath(installRoot));
        return normalized.StartsWith("/opt/xdm", StringComparison.Ordinal)
            || normalized.StartsWith("/usr/lib/xdm", StringComparison.Ordinal)
            || normalized.StartsWith("/usr/share/xdm", StringComparison.Ordinal);
    }

    private static void PruneStagingTrees(string channelDirectory, string protectedVersionDirectory)
    {
        if (!Directory.Exists(channelDirectory))
        {
            return;
        }
        DateTimeOffset cutoff = DateTimeOffset.UtcNow.Subtract(UpdateArtifactRetention);
        foreach (string temporary in Directory.EnumerateFiles(channelDirectory, "*.downloading", SearchOption.AllDirectories))
        {
            TryDeleteFile(temporary);
        }
        foreach (string runner in Directory.EnumerateDirectories(channelDirectory, "runner", SearchOption.AllDirectories))
        {
            if (Directory.GetLastWriteTimeUtc(runner) < cutoff.UtcDateTime)
            {
                TryDeleteDirectory(runner);
            }
        }

        string protectedFullPath = Path.TrimEndingDirectorySeparator(Path.GetFullPath(protectedVersionDirectory));
        DirectoryInfo[] versions = new DirectoryInfo(channelDirectory)
            .EnumerateDirectories()
            .Where(directory => !string.Equals(
                Path.TrimEndingDirectorySeparator(directory.FullName),
                protectedFullPath,
                OperatingSystem.IsWindows() ? StringComparison.OrdinalIgnoreCase : StringComparison.Ordinal))
            .OrderByDescending(directory => directory.LastWriteTimeUtc)
            .ToArray();
        foreach (DirectoryInfo stale in versions.Skip(MaximumRetainedUpdateVersions - 1))
        {
            if (stale.LastWriteTimeUtc < cutoff.UtcDateTime)
            {
                TryDeleteDirectory(stale.FullName);
            }
        }
    }

    private static class SemanticVersion
    {
        public static bool IsValid(string value)
            => TryParse(value, out _);

        public static bool IsUpdateAvailable(
            string available,
            string current,
            UpdateChannel channel)
        {
            if (string.Equals(available, current, StringComparison.OrdinalIgnoreCase))
            {
                return false;
            }
            if (channel == UpdateChannel.Stable)
            {
                return Compare(available, current) > 0;
            }
            if (!TryParse(available, out VersionParts availableParts)
                || !TryParse(current, out VersionParts currentParts))
            {
                return string.Compare(available, current, StringComparison.OrdinalIgnoreCase) > 0;
            }

            int core = CompareCore(availableParts, currentParts);
            if (core != 0)
            {
                return core > 0;
            }

            string channelPrefix = channel.ToManifestName();
            bool currentIsSameChannel = currentParts.PreRelease?.StartsWith(
                channelPrefix,
                StringComparison.OrdinalIgnoreCase) == true;
            return !currentIsSameChannel || ComparePreRelease(availableParts.PreRelease, currentParts.PreRelease) > 0;
        }

        public static int Compare(string left, string right)
        {
            if (!TryParse(left, out VersionParts leftParts)
                || !TryParse(right, out VersionParts rightParts))
            {
                return string.Compare(left, right, StringComparison.OrdinalIgnoreCase);
            }

            int core = CompareCore(leftParts, rightParts);
            return core != 0
                ? core
                : ComparePreRelease(leftParts.PreRelease, rightParts.PreRelease);
        }

        private static int CompareCore(VersionParts left, VersionParts right)
        {
            int result = left.Major.CompareTo(right.Major);
            if (result == 0) result = left.Minor.CompareTo(right.Minor);
            if (result == 0) result = left.Patch.CompareTo(right.Patch);
            return result;
        }

        private static int ComparePreRelease(string? left, string? right)
        {
            if (left is null && right is null) return 0;
            if (left is null) return 1;
            if (right is null) return -1;

            string[] leftIdentifiers = left.Split('.');
            string[] rightIdentifiers = right.Split('.');
            int shared = Math.Min(leftIdentifiers.Length, rightIdentifiers.Length);
            for (int index = 0; index < shared; index++)
            {
                string leftIdentifier = leftIdentifiers[index];
                string rightIdentifier = rightIdentifiers[index];
                bool leftNumeric = int.TryParse(
                    leftIdentifier,
                    NumberStyles.None,
                    CultureInfo.InvariantCulture,
                    out int leftNumber);
                bool rightNumeric = int.TryParse(
                    rightIdentifier,
                    NumberStyles.None,
                    CultureInfo.InvariantCulture,
                    out int rightNumber);
                int comparison = leftNumeric && rightNumeric
                    ? leftNumber.CompareTo(rightNumber)
                    : leftNumeric
                        ? -1
                        : rightNumeric
                            ? 1
                            : string.Compare(leftIdentifier, rightIdentifier, StringComparison.OrdinalIgnoreCase);
                if (comparison != 0)
                {
                    return comparison;
                }
            }
            return leftIdentifiers.Length.CompareTo(rightIdentifiers.Length);
        }

        private static bool TryParse(string value, out VersionParts parts)
        {
            parts = default;
            if (string.IsNullOrWhiteSpace(value)) return false;
            string withoutMetadata = value.Split('+', 2)[0];
            string[] preReleaseSplit = withoutMetadata.Split('-', 2);
            string[] core = preReleaseSplit[0].Split('.');
            if (core.Length < 2 || core.Length > 3
                || !int.TryParse(core[0], NumberStyles.None, CultureInfo.InvariantCulture, out int major)
                || !int.TryParse(core[1], NumberStyles.None, CultureInfo.InvariantCulture, out int minor)
                || (core.Length == 3 && !int.TryParse(core[2], NumberStyles.None, CultureInfo.InvariantCulture, out _)))
            {
                return false;
            }
            int patch = core.Length == 3 ? int.Parse(core[2], CultureInfo.InvariantCulture) : 0;
            string? preRelease = preReleaseSplit.Length == 2 ? preReleaseSplit[1] : null;
            if (preRelease is not null
                && (preRelease.Length == 0
                    || preRelease.Split('.').Any(static identifier => identifier.Length == 0)))
            {
                return false;
            }
            parts = new VersionParts(major, minor, patch, preRelease);
            return true;
        }

        private readonly record struct VersionParts(int Major, int Minor, int Patch, string? PreRelease);
    }

}
