using System.Diagnostics;
using XDM.Core.Abstractions;

namespace XDM.Platform;

public sealed class DesktopNotificationService : IDesktopNotificationService
{
    public async Task ShowAsync(string title, string message, CancellationToken cancellationToken = default)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(title);
        ArgumentException.ThrowIfNullOrWhiteSpace(message);

        NotificationCommand? command = NotificationCommandFactory.Create(title, message);
        if (command is null)
        {
            return;
        }

        try
        {
            using Process process = new() { StartInfo = command.CreateStartInfo() };
            if (!process.Start())
            {
                return;
            }

            await process.WaitForExitAsync(cancellationToken).ConfigureAwait(false);
        }
        catch (Exception exception) when (exception is InvalidOperationException or System.ComponentModel.Win32Exception or IOException)
        {
            // Notifications are best-effort and must never interrupt download processing.
        }
    }
}

public sealed record NotificationCommand(string FileName, IReadOnlyList<string> Arguments)
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

public static class NotificationCommandFactory
{
    public static NotificationCommand? Create(string title, string message)
    {
        if (OperatingSystem.IsLinux())
        {
            return new NotificationCommand("notify-send", ["--app-name=XDM", title, message]);
        }

        if (OperatingSystem.IsMacOS())
        {
            string script = $"display notification {QuoteAppleScript(message)} with title {QuoteAppleScript(title)}";
            return new NotificationCommand("osascript", ["-e", script]);
        }

        if (OperatingSystem.IsWindows())
        {
            string escapedTitle = EscapeXml(title);
            string escapedMessage = EscapeXml(message);
            string script = string.Concat(
                "$xml = New-Object Windows.Data.Xml.Dom.XmlDocument;",
                "$xml.LoadXml('<toast><visual><binding template=\"ToastGeneric\"><text>", escapedTitle,
                "</text><text>", escapedMessage,
                "</text></binding></visual></toast>');",
                "$toast = [Windows.UI.Notifications.ToastNotification]::new($xml);",
                "[Windows.UI.Notifications.ToastNotificationManager]::CreateToastNotifier('XDM').Show($toast)");
            return new NotificationCommand("powershell.exe", ["-NoProfile", "-NonInteractive", "-Command", script]);
        }

        return null;
    }

    private static string EscapeXml(string value)
        => System.Security.SecurityElement.Escape(value) ?? string.Empty;

    private static string QuoteAppleScript(string value)
        => $"\"{value.Replace("\\", "\\\\", StringComparison.Ordinal).Replace("\"", "\\\"", StringComparison.Ordinal)}\"";
}
