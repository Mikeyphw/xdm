using System.Diagnostics;
using XDM.Core.Settings;

namespace XDM.DownloadEngine.Aria2;

public sealed class Aria2ManagedProcess : IDisposable
{
    private readonly object _sync = new();
    private Process? _process;
    private string? _lastError;
    private bool _disposed;

    public bool IsRunning
    {
        get
        {
            lock (_sync)
            {
                return _process is { HasExited: false };
            }
        }
    }

    public int? ProcessId
    {
        get
        {
            lock (_sync)
            {
                return _process is { HasExited: false } process ? process.Id : null;
            }
        }
    }

    public string? LastError
    {
        get
        {
            lock (_sync)
            {
                return _lastError;
            }
        }
    }

    public Task StartAsync(
        Aria2IntegrationSettings settings,
        CancellationToken cancellationToken = default)
    {
        ObjectDisposedException.ThrowIf(_disposed, this);
        cancellationToken.ThrowIfCancellationRequested();
        Aria2IntegrationSettings normalized = settings.Normalize();
        if (normalized.ConnectionMode != Aria2ConnectionMode.ManagedProcess)
        {
            throw new InvalidOperationException("A managed aria2 process can only be started in managed-process mode.");
        }

        lock (_sync)
        {
            if (_process is { HasExited: false })
            {
                return Task.CompletedTask;
            }

            _process?.Dispose();
            _process = null;
            _lastError = null;
            PrepareSessionFile(normalized);

            ProcessStartInfo startInfo = new()
            {
                FileName = normalized.ExecutablePath,
                UseShellExecute = false,
                CreateNoWindow = true,
                RedirectStandardOutput = true,
                RedirectStandardError = true,
                WorkingDirectory = ResolveWorkingDirectory(normalized)
            };
            AddArguments(startInfo, normalized);

            Process process = new()
            {
                StartInfo = startInfo,
                EnableRaisingEvents = true
            };
            process.OutputDataReceived += OnOutputDataReceived;
            process.ErrorDataReceived += OnErrorDataReceived;
            process.Exited += OnExited;
            try
            {
                if (!process.Start())
                {
                    process.Dispose();
                    throw new InvalidOperationException("aria2c did not start.");
                }

                process.BeginOutputReadLine();
                process.BeginErrorReadLine();
                _process = process;
            }
            catch
            {
                process.OutputDataReceived -= OnOutputDataReceived;
                process.ErrorDataReceived -= OnErrorDataReceived;
                process.Exited -= OnExited;
                process.Dispose();
                throw;
            }
        }

        return Task.CompletedTask;
    }

    public async Task StopAsync(CancellationToken cancellationToken = default)
    {
        Process? process;
        lock (_sync)
        {
            process = _process;
        }

        if (process is null || process.HasExited)
        {
            return;
        }

        try
        {
            using CancellationTokenSource timeout = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
            timeout.CancelAfter(TimeSpan.FromSeconds(3));
            await process.WaitForExitAsync(timeout.Token).ConfigureAwait(false);
        }
        catch (OperationCanceledException) when (!cancellationToken.IsCancellationRequested)
        {
            if (!process.HasExited)
            {
                process.Kill(entireProcessTree: true);
                await process.WaitForExitAsync(cancellationToken).ConfigureAwait(false);
            }
        }
    }

    public void Dispose()
    {
        if (_disposed)
        {
            return;
        }

        _disposed = true;
        Process? process;
        lock (_sync)
        {
            process = _process;
            _process = null;
        }

        if (process is not null)
        {
            process.OutputDataReceived -= OnOutputDataReceived;
            process.ErrorDataReceived -= OnErrorDataReceived;
            process.Exited -= OnExited;
            if (!process.HasExited)
            {
                process.Kill(entireProcessTree: true);
                process.WaitForExit(3000);
            }
            process.Dispose();
        }

        GC.SuppressFinalize(this);
    }

    private static void PrepareSessionFile(Aria2IntegrationSettings settings)
    {
        if (!settings.SaveSession)
        {
            return;
        }

        string? directory = Path.GetDirectoryName(settings.SessionFilePath);
        if (!string.IsNullOrWhiteSpace(directory))
        {
            Directory.CreateDirectory(directory);
        }

        if (!File.Exists(settings.SessionFilePath))
        {
            using FileStream stream = File.Create(settings.SessionFilePath);
        }
    }

    private static string ResolveWorkingDirectory(Aria2IntegrationSettings settings)
    {
        string? sessionDirectory = Path.GetDirectoryName(settings.SessionFilePath);
        if (!string.IsNullOrWhiteSpace(sessionDirectory))
        {
            return sessionDirectory;
        }

        return Environment.CurrentDirectory;
    }

    private static void AddArguments(ProcessStartInfo startInfo, Aria2IntegrationSettings settings)
    {
        Uri endpoint = settings.GetRpcUri();
        int port = endpoint.IsDefaultPort
            ? endpoint.Scheme == Uri.UriSchemeHttps ? 443 : 80
            : endpoint.Port;
        startInfo.ArgumentList.Add("--enable-rpc=true");
        startInfo.ArgumentList.Add("--rpc-listen-all=false");
        startInfo.ArgumentList.Add($"--rpc-listen-port={port}");
        if (settings.RpcSecret.Length > 0)
        {
            startInfo.ArgumentList.Add($"--rpc-secret={settings.RpcSecret}");
        }
        startInfo.ArgumentList.Add($"--max-concurrent-downloads={settings.MaxConcurrentDownloads}");
        startInfo.ArgumentList.Add($"--split={settings.SplitCount}");
        startInfo.ArgumentList.Add($"--max-connection-per-server={settings.SplitCount}");
        startInfo.ArgumentList.Add($"--min-split-size={settings.MinimumSplitSizeBytes}");
        startInfo.ArgumentList.Add($"--continue={settings.ContinueDownloads.ToString().ToLowerInvariant()}");
        startInfo.ArgumentList.Add($"--check-certificate={settings.CheckCertificate.ToString().ToLowerInvariant()}");
        startInfo.ArgumentList.Add("--console-log-level=warn");
        startInfo.ArgumentList.Add("--summary-interval=0");

        if (settings.SaveSession)
        {
            startInfo.ArgumentList.Add($"--input-file={settings.SessionFilePath}");
            startInfo.ArgumentList.Add($"--save-session={settings.SessionFilePath}");
            startInfo.ArgumentList.Add("--save-session-interval=30");
        }

        foreach (string argument in settings.AdditionalArguments.Split(
                     ["\r\n", "\n"],
                     StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries))
        {
            startInfo.ArgumentList.Add(argument);
        }
    }

    private void OnOutputDataReceived(object sender, DataReceivedEventArgs eventArgs)
    {
        if (!string.IsNullOrWhiteSpace(eventArgs.Data)
            && eventArgs.Data.Contains("error", StringComparison.OrdinalIgnoreCase))
        {
            lock (_sync)
            {
                _lastError = eventArgs.Data;
            }
        }
    }

    private void OnErrorDataReceived(object sender, DataReceivedEventArgs eventArgs)
    {
        if (string.IsNullOrWhiteSpace(eventArgs.Data))
        {
            return;
        }

        lock (_sync)
        {
            _lastError = eventArgs.Data;
        }
    }

    private void OnExited(object? sender, EventArgs eventArgs)
    {
        lock (_sync)
        {
            if (sender is Process process && process.ExitCode != 0 && string.IsNullOrWhiteSpace(_lastError))
            {
                _lastError = $"aria2c exited with code {process.ExitCode}.";
            }
        }
    }
}
