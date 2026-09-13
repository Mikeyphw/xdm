using System.ComponentModel;
using System.Diagnostics;
using System.Text;
using XDM.Core.Abstractions;

namespace XDM.Platform;

public sealed class DesktopNotificationService : IDesktopNotificationService
{
    public static readonly TimeSpan DefaultDeliveryTimeout = TimeSpan.FromSeconds(8);

    private readonly IDesktopNotificationCommandFactory _commandFactory;
    private readonly INotificationCommandExecutor _commandExecutor;
    private readonly TimeSpan _deliveryTimeout;

    public DesktopNotificationService()
        : this(
            DesktopNotificationCommandFactory.Current,
            new ProcessNotificationCommandExecutor(),
            DefaultDeliveryTimeout)
    {
    }

    public DesktopNotificationService(
        IDesktopNotificationCommandFactory commandFactory,
        INotificationCommandExecutor commandExecutor,
        TimeSpan deliveryTimeout)
    {
        _commandFactory = commandFactory ?? throw new ArgumentNullException(nameof(commandFactory));
        _commandExecutor = commandExecutor ?? throw new ArgumentNullException(nameof(commandExecutor));
        ArgumentOutOfRangeException.ThrowIfLessThanOrEqual(deliveryTimeout, TimeSpan.Zero);
        _deliveryTimeout = deliveryTimeout;
    }

    public async Task<DesktopNotificationDeliveryResult> ShowAsync(
        string title,
        string message,
        CancellationToken cancellationToken = default)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(title);
        ArgumentException.ThrowIfNullOrWhiteSpace(message);
        cancellationToken.ThrowIfCancellationRequested();

        NotificationCommand? command = _commandFactory.Create(title.Trim(), message.Trim());
        if (command is null)
        {
            return DesktopNotificationDeliveryResult.Unsupported("No desktop notification backend is available for this platform.");
        }

        using CancellationTokenSource timeoutCancellation = new(_deliveryTimeout);
        using CancellationTokenSource linkedCancellation = CancellationTokenSource.CreateLinkedTokenSource(
            cancellationToken,
            timeoutCancellation.Token);
        try
        {
            NotificationCommandExecutionResult execution = await _commandExecutor
                .ExecuteAsync(command, linkedCancellation.Token)
                .ConfigureAwait(false);
            if (execution.ExitCode == 0)
            {
                return DesktopNotificationDeliveryResult.DeliveredBy(command.Channel);
            }

            string failure = FirstNonEmpty(
                execution.StandardError,
                execution.StandardOutput,
                $"Notification command exited with code {execution.ExitCode}.");
            return DesktopNotificationDeliveryResult.Failed(command.Channel, failure);
        }
        catch (OperationCanceledException) when (timeoutCancellation.IsCancellationRequested
            && !cancellationToken.IsCancellationRequested)
        {
            return DesktopNotificationDeliveryResult.TimedOutResult(command.Channel, _deliveryTimeout);
        }
        catch (Exception exception) when (exception is InvalidOperationException
            or Win32Exception
            or IOException
            or UnauthorizedAccessException)
        {
            return DesktopNotificationDeliveryResult.Failed(command.Channel, exception.Message);
        }
    }

    private static string FirstNonEmpty(params string?[] values)
    {
        foreach (string? value in values)
        {
            if (!string.IsNullOrWhiteSpace(value))
            {
                return value.Trim();
            }
        }

        return "Desktop notification delivery failed.";
    }
}

public enum DesktopNotificationPlatform
{
    Unsupported,
    Linux,
    MacOS,
    Windows
}

public sealed record NotificationCommand(
    string FileName,
    IReadOnlyList<string> Arguments,
    string Channel)
{
    public ProcessStartInfo CreateStartInfo()
    {
        ProcessStartInfo startInfo = new(FileName)
        {
            UseShellExecute = false,
            CreateNoWindow = true,
            RedirectStandardOutput = true,
            RedirectStandardError = true
        };
        foreach (string argument in Arguments)
        {
            startInfo.ArgumentList.Add(argument);
        }

        return startInfo;
    }
}

public sealed record NotificationCommandExecutionResult(
    int ExitCode,
    string StandardOutput,
    string StandardError);

public interface INotificationCommandExecutor
{
    Task<NotificationCommandExecutionResult> ExecuteAsync(
        NotificationCommand command,
        CancellationToken cancellationToken = default);
}

public interface IDesktopNotificationCommandFactory
{
    NotificationCommand? Create(string title, string message);
}

public sealed class DesktopNotificationCommandFactory : IDesktopNotificationCommandFactory
{
    public const string WindowsAppUserModelId = "com.subhra74.xdm.modern";
    private readonly DesktopNotificationPlatform _platform;
    private readonly string? _windowsExecutablePath;

    public static DesktopNotificationCommandFactory Current { get; } = new(
        DetectCurrentPlatform(),
        Environment.ProcessPath);

    public DesktopNotificationCommandFactory(
        DesktopNotificationPlatform platform,
        string? windowsExecutablePath = null)
    {
        _platform = platform;
        _windowsExecutablePath = windowsExecutablePath;
    }

    public NotificationCommand? Create(string title, string message)
        => Create(title, message, _platform, _windowsExecutablePath);

    public static NotificationCommand? Create(
        string title,
        string message,
        DesktopNotificationPlatform platform,
        string? windowsExecutablePath = null)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(title);
        ArgumentException.ThrowIfNullOrWhiteSpace(message);
        return platform switch
        {
            DesktopNotificationPlatform.Linux => new NotificationCommand(
                "notify-send",
                ["--app-name=XDM", "--expire-time=7000", title, message],
                "linux-notify-send"),
            DesktopNotificationPlatform.MacOS => new NotificationCommand(
                "osascript",
                ["-e", $"display notification {QuoteAppleScript(message)} with title {QuoteAppleScript(title)}"],
                "macos-osascript"),
            DesktopNotificationPlatform.Windows => CreateWindowsToastCommand(title, message, windowsExecutablePath),
            _ => null
        };
    }

    private static NotificationCommand CreateWindowsToastCommand(
        string title,
        string message,
        string? executablePath)
    {
        string resolvedExecutable = string.IsNullOrWhiteSpace(executablePath)
            ? "powershell.exe"
            : executablePath;
        string escapedTitle = EscapeXml(title);
        string escapedMessage = EscapeXml(message);
        string script = string.Concat(
            "$ErrorActionPreference='Stop';",
            "$aumid=", QuotePowerShell(WindowsAppUserModelId), ";",
            "$shortcut=Join-Path ([Environment]::GetFolderPath('StartMenu')) 'Programs\\Xtreme Download Manager.lnk';",
            "if(-not (Test-Path -LiteralPath $shortcut)){",
            "$shell=New-Object -ComObject WScript.Shell;",
            "$link=$shell.CreateShortcut($shortcut);",
            "$link.TargetPath=", QuotePowerShell(resolvedExecutable), ";",
            "$link.WorkingDirectory=", QuotePowerShell(Path.GetDirectoryName(resolvedExecutable) ?? Environment.CurrentDirectory), ";",
            "$link.Description='Xtreme Download Manager';",
            "try{$link.AppUserModelID=$aumid}catch{};",
            "$link.Save();",
            "}",
            "[Windows.UI.Notifications.ToastNotificationManager,Windows.UI.Notifications,ContentType=WindowsRuntime] | Out-Null;",
            "$xml=New-Object Windows.Data.Xml.Dom.XmlDocument;",
            "$xml.LoadXml(", QuotePowerShell("<toast><visual><binding template=\"ToastGeneric\"><text>" + escapedTitle + "</text><text>" + escapedMessage + "</text></binding></visual></toast>"), ");",
            "$toast=[Windows.UI.Notifications.ToastNotification]::new($xml);",
            "[Windows.UI.Notifications.ToastNotificationManager]::CreateToastNotifier($aumid).Show($toast);"
        );
        return new NotificationCommand(
            "powershell.exe",
            ["-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-Command", script],
            "windows-toast-aumid");
    }

    private static DesktopNotificationPlatform DetectCurrentPlatform()
    {
        if (OperatingSystem.IsLinux())
        {
            return DesktopNotificationPlatform.Linux;
        }

        if (OperatingSystem.IsMacOS())
        {
            return DesktopNotificationPlatform.MacOS;
        }

        if (OperatingSystem.IsWindows())
        {
            return DesktopNotificationPlatform.Windows;
        }

        return DesktopNotificationPlatform.Unsupported;
    }

    private static string EscapeXml(string value)
        => System.Security.SecurityElement.Escape(value) ?? string.Empty;

    private static string QuoteAppleScript(string value)
        => $"\"{value.Replace("\\", "\\\\", StringComparison.Ordinal).Replace("\"", "\\\"", StringComparison.Ordinal)}\"";

    private static string QuotePowerShell(string value)
        => "'" + value.Replace("'", "''", StringComparison.Ordinal) + "'";
}

public sealed class ProcessNotificationCommandExecutor : INotificationCommandExecutor
{
    private const int MaximumCapturedCharacters = 4096;
    private static readonly TimeSpan KillWaitTimeout = TimeSpan.FromSeconds(2);

    public async Task<NotificationCommandExecutionResult> ExecuteAsync(
        NotificationCommand command,
        CancellationToken cancellationToken = default)
    {
        ArgumentNullException.ThrowIfNull(command);
        BoundedCapture output = new(MaximumCapturedCharacters);
        BoundedCapture error = new(MaximumCapturedCharacters);
        using Process process = new() { StartInfo = command.CreateStartInfo(), EnableRaisingEvents = true };
        process.OutputDataReceived += (_, eventArgs) => output.AppendLine(eventArgs.Data);
        process.ErrorDataReceived += (_, eventArgs) => error.AppendLine(eventArgs.Data);

        if (!process.Start())
        {
            return new NotificationCommandExecutionResult(-1, string.Empty, "The notification command could not be started.");
        }

        process.BeginOutputReadLine();
        process.BeginErrorReadLine();
        try
        {
            await process.WaitForExitAsync(cancellationToken).ConfigureAwait(false);
        }
        catch (OperationCanceledException)
        {
            TryKillProcessTree(process);
            using CancellationTokenSource killWait = new(KillWaitTimeout);
            try
            {
                await process.WaitForExitAsync(killWait.Token).ConfigureAwait(false);
            }
            catch (OperationCanceledException)
            {
            }

            throw;
        }

        process.WaitForExit();
        return new NotificationCommandExecutionResult(
            process.HasExited ? process.ExitCode : -1,
            output.ToString(),
            error.ToString());
    }

    private static void TryKillProcessTree(Process process)
    {
        try
        {
            if (!process.HasExited)
            {
                process.Kill(entireProcessTree: true);
            }
        }
        catch (Exception exception) when (exception is InvalidOperationException or Win32Exception or NotSupportedException)
        {
        }
    }

    private sealed class BoundedCapture(int maximumCharacters)
    {
        private readonly object _sync = new();
        private readonly StringBuilder _builder = new(Math.Min(maximumCharacters, 1024));
        private bool _truncated;

        public void AppendLine(string? value)
        {
            if (value is null)
            {
                return;
            }

            lock (_sync)
            {
                if (_builder.Length >= maximumCharacters)
                {
                    _truncated = true;
                    return;
                }

                int remaining = maximumCharacters - _builder.Length;
                string line = value.Length + Environment.NewLine.Length <= remaining
                    ? value + Environment.NewLine
                    : value[..Math.Max(0, remaining)];
                _builder.Append(line);
                if (line.Length < value.Length + Environment.NewLine.Length)
                {
                    _truncated = true;
                }
            }
        }

        public override string ToString()
        {
            lock (_sync)
            {
                return _truncated
                    ? _builder.ToString() + Environment.NewLine + "[output truncated]"
                    : _builder.ToString();
            }
        }
    }
}

// Backwards-compatible facade for older tests and integrations that called the static factory directly.
public static class NotificationCommandFactory
{
    public static NotificationCommand? Create(string title, string message)
        => DesktopNotificationCommandFactory.Current.Create(title, message);

    public static NotificationCommand? Create(
        string title,
        string message,
        DesktopNotificationPlatform platform,
        string? windowsExecutablePath = null)
        => DesktopNotificationCommandFactory.Create(title, message, platform, windowsExecutablePath);
}
