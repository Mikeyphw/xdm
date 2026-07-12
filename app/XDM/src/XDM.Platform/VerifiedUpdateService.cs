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
    private static readonly HashSet<string> AllowedPackageExtensions = new(
        [".zip", ".msi", ".exe", ".deb", ".rpm", ".appimage", ".gz"],
        StringComparer.OrdinalIgnoreCase);
    private static readonly HashSet<string> AllowedHosts = new(
        ["github.com", "objects.githubusercontent.com", "release-assets.githubusercontent.com", "raw.githubusercontent.com"],
        StringComparer.OrdinalIgnoreCase);
    private static readonly JsonSerializerOptions ManifestJsonOptions = new(JsonSerializerDefaults.Web);

    private readonly HttpClient _httpClient;
    private readonly IPlatformInfo _platformInfo;
    private readonly string _updateRoot;
    private readonly Uri _manifestUri;

    public VerifiedUpdateService(HttpClient httpClient, IPlatformInfo platformInfo)
        : this(
            httpClient,
            platformInfo,
            Path.Combine(
                Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
                "XDM",
                "updates"),
            ModernFeaturePolicy.UpdateManifest)
    {
    }

    internal VerifiedUpdateService(
        HttpClient httpClient,
        IPlatformInfo platformInfo,
        string updateRoot,
        Uri manifestUri)
    {
        _httpClient = httpClient;
        _platformInfo = platformInfo;
        _updateRoot = Path.GetFullPath(updateRoot);
        _manifestUri = manifestUri;
    }

    public async Task<UpdateCheckResult> CheckAsync(CancellationToken cancellationToken = default)
    {
        ValidateHttpsUri(_manifestUri, nameof(_manifestUri));
        using HttpResponseMessage response = await _httpClient.GetAsync(
            _manifestUri,
            HttpCompletionOption.ResponseHeadersRead,
            cancellationToken).ConfigureAwait(false);
        response.EnsureSuccessStatusCode();
        ValidateTrustedHttpsHost(response.RequestMessage?.RequestUri ?? _manifestUri);
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
        ValidateManifest(manifest);

        string runtimeIdentifier = ResolveRuntimeIdentifier(_platformInfo);
        UpdatePackageDescriptor? package = manifest.Packages!
            .FirstOrDefault(item => string.Equals(
                item.RuntimeIdentifier,
                runtimeIdentifier,
                StringComparison.OrdinalIgnoreCase));
        bool updateAvailable = SemanticVersion.Compare(manifest.Version, ProductVersion.Current) > 0;
        Uri? releaseNotes = TryCreateHttpsUri(manifest.ReleaseNotesUrl);
        string message = updateAvailable
            ? package is null
                ? $"XDM {manifest.Version} is available, but this platform has no package."
                : $"XDM {manifest.Version} is available for {runtimeIdentifier}."
            : $"XDM {ProductVersion.Current} is current.";
        return new UpdateCheckResult(
            ProductVersion.Current,
            manifest.Version,
            updateAvailable,
            releaseNotes,
            package,
            message);
    }

    public async Task<StagedUpdateResult> StageAsync(
        UpdateCheckResult update,
        IProgress<double>? progress = null,
        CancellationToken cancellationToken = default)
    {
        ArgumentNullException.ThrowIfNull(update);
        UpdatePackageDescriptor package = update.Package
            ?? throw new InvalidOperationException("No compatible update package is available.");
        ValidatePackage(package);
        Uri packageUri = new(package.Url, UriKind.Absolute);
        ValidatePackageUri(packageUri);

        string versionDirectory = Path.Combine(_updateRoot, SanitizeSegment(update.AvailableVersion));
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
            using IncrementalHash hash = IncrementalHash.CreateHash(HashAlgorithmName.SHA256);
            byte[] buffer = new byte[64 * 1024];
            long downloaded = 0;
            await using (FileStream destination = new(
                temporaryPath,
                FileMode.Create,
                FileAccess.Write,
                FileShare.None,
                64 * 1024,
                FileOptions.Asynchronous | FileOptions.SequentialScan))
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

                    hash.AppendData(buffer, 0, read);
                    await destination.WriteAsync(buffer.AsMemory(0, read), cancellationToken).ConfigureAwait(false);
                    progress?.Report((double)downloaded / package.SizeBytes);
                }

                await destination.FlushAsync(cancellationToken).ConfigureAwait(false);
            }

            if (downloaded != package.SizeBytes)
            {
                throw new InvalidDataException("The update package is incomplete.");
            }

            string actualHash = Convert.ToHexString(hash.GetHashAndReset());
            if (!string.Equals(actualHash, package.Sha256, StringComparison.OrdinalIgnoreCase))
            {
                throw new InvalidDataException("The update package SHA-256 does not match the manifest.");
            }

            File.Move(temporaryPath, destinationPath, overwrite: true);
            return new StagedUpdateResult(update.AvailableVersion, destinationPath, actualHash, downloaded);
        }
        finally
        {
            if (File.Exists(temporaryPath))
            {
                File.Delete(temporaryPath);
            }
        }
    }

    private static void ValidateManifest(UpdateManifestDocument manifest)
    {
        if (manifest.SchemaVersion != 1)
        {
            throw new InvalidDataException($"Unsupported update manifest schema {manifest.SchemaVersion}.");
        }
        if (!SemanticVersion.IsValid(manifest.Version))
        {
            throw new InvalidDataException("The update manifest version is invalid.");
        }
        if (manifest.Packages is null || manifest.Packages.Count == 0 || manifest.Packages.Count > 32)
        {
            throw new InvalidDataException("The update manifest package list is invalid.");
        }
        foreach (UpdatePackageDescriptor package in manifest.Packages)
        {
            ValidatePackage(package);
        }
    }

    private static void ValidatePackage(UpdatePackageDescriptor package)
    {
        if (string.IsNullOrWhiteSpace(package.RuntimeIdentifier)
            || string.IsNullOrWhiteSpace(package.Url)
            || string.IsNullOrWhiteSpace(package.Sha256)
            || string.IsNullOrWhiteSpace(package.FileName)
            || package.SizeBytes is <= 0 or > MaximumPackageBytes
            || package.Sha256.Length != 64
            || !IsHexSha256(package.Sha256)
            || !Uri.TryCreate(package.Url, UriKind.Absolute, out Uri? packageUri))
        {
            throw new InvalidDataException("The update package metadata is invalid.");
        }
        ValidatePackageUri(packageUri);
        string extension = Path.GetExtension(package.FileName);
        if (!AllowedPackageExtensions.Contains(extension)
            && !package.FileName.EndsWith(".tar.gz", StringComparison.OrdinalIgnoreCase))
        {
            throw new InvalidDataException("The update package file type is not allowed.");
        }
    }

    private static bool IsHexSha256(string value)
    {
        foreach (char character in value)
        {
            if (!Uri.IsHexDigit(character))
            {
                return false;
            }
        }

        return true;
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

    private static class SemanticVersion
    {
        public static bool IsValid(string value)
            => TryParse(value, out _);

        public static int Compare(string left, string right)
        {
            if (!TryParse(left, out VersionParts leftParts)
                || !TryParse(right, out VersionParts rightParts))
            {
                return string.Compare(left, right, StringComparison.OrdinalIgnoreCase);
            }

            int core = leftParts.Major.CompareTo(rightParts.Major);
            if (core == 0) core = leftParts.Minor.CompareTo(rightParts.Minor);
            if (core == 0) core = leftParts.Patch.CompareTo(rightParts.Patch);
            if (core != 0) return core;
            if (leftParts.PreRelease is null && rightParts.PreRelease is not null) return 1;
            if (leftParts.PreRelease is not null && rightParts.PreRelease is null) return -1;
            return string.Compare(leftParts.PreRelease, rightParts.PreRelease, StringComparison.OrdinalIgnoreCase);
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
            parts = new VersionParts(major, minor, patch, preReleaseSplit.Length == 2 ? preReleaseSplit[1] : null);
            return true;
        }

        private readonly record struct VersionParts(int Major, int Minor, int Patch, string? PreRelease);
    }
}
