using System.Text;
using System.Text.Json;
using XDM.Core.Diagnostics;

namespace XDM.Diagnostics.Tests;

public sealed class Rem18DiagnosticPrivacyBoundaryTests
{
    [Fact]
    public async Task RedactedJsonWriterRemovesSecretsAcrossSupportBundleBoundary()
    {
        var payload = new
        {
            Header = "Authorization: Bearer secret-token Cookie: session=secret-cookie",
            Url = "https://user:password@example.test/path/file.mp4?access_token=abc123&api_key=def456",
            WindowsPath = @"C:\\Users\\Mikey\\Downloads\\private-video.mp4",
            UnixPath = "/home/mikey/private/downloads/private-video.mp4",
            Nested = new
            {
                Proxy = "Proxy-Authorization: Basic dXNlcjpwYXNz",
                Refresh = "refresh_token=refresh-secret"
            }
        };

        await using MemoryStream stream = new();
        await DiagnosticRedactor.WriteRedactedJsonAsync(
            stream,
            payload,
            new JsonSerializerOptions { WriteIndented = true },
            CancellationToken.None);
        string redacted = Encoding.UTF8.GetString(stream.ToArray());

        Assert.DoesNotContain("secret-token", redacted, StringComparison.Ordinal);
        Assert.DoesNotContain("secret-cookie", redacted, StringComparison.Ordinal);
        Assert.DoesNotContain("user:password", redacted, StringComparison.Ordinal);
        Assert.DoesNotContain("abc123", redacted, StringComparison.Ordinal);
        Assert.DoesNotContain("def456", redacted, StringComparison.Ordinal);
        Assert.DoesNotContain("dXNlcjpwYXNz", redacted, StringComparison.Ordinal);
        Assert.DoesNotContain("refresh-secret", redacted, StringComparison.Ordinal);
        Assert.DoesNotContain("Mikey", redacted, StringComparison.OrdinalIgnoreCase);
        Assert.Contains("[REDACTED]", redacted, StringComparison.Ordinal);
        Assert.Contains("[LOCAL-PATH]", redacted, StringComparison.Ordinal);
    }

    [Fact]
    public void Rem18FaultMatrixIncludesDiagnosticSupportBundlePrivacyScenario()
    {
        string root = FindRepositoryRoot(AppContext.BaseDirectory);
        string matrix = File.ReadAllText(Path.Combine(root, "app", "XDM", "eng", "rem18-fault-injection-matrix.json"));

        Assert.Contains("diagnostics-post-crash-redaction-bundle", matrix, StringComparison.Ordinal);
        Assert.Contains("S14-10", matrix, StringComparison.Ordinal);
        Assert.Contains("bundle is published atomically", matrix, StringComparison.Ordinal);
    }

    private static string FindRepositoryRoot(string startPath)
    {
        DirectoryInfo? current = new(Path.GetFullPath(startPath));
        while (current is not null)
        {
            if (File.Exists(Path.Combine(current.FullName, ".devtool.toml"))
                && File.Exists(Path.Combine(current.FullName, "app", "XDM", "XDM.Modern.sln")))
            {
                return current.FullName;
            }
            current = current.Parent;
        }
        throw new DirectoryNotFoundException("Repository root not found.");
    }
}
