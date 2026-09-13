using XDM.Diagnostics;

namespace XDM.App.Services;

internal sealed class CrashDiagnosticWiring : IDisposable
{
    private readonly IDiagnosticEventStore _diagnostics;
    private bool _disposed;

    private CrashDiagnosticWiring(IDiagnosticEventStore diagnostics)
    {
        _diagnostics = diagnostics;
    }

    public static CrashDiagnosticWiring Attach(IDiagnosticEventStore diagnostics)
    {
        CrashDiagnosticWiring wiring = new(diagnostics);
        AppDomain.CurrentDomain.UnhandledException += wiring.OnUnhandledException;
        TaskScheduler.UnobservedTaskException += wiring.OnUnobservedTaskException;
        return wiring;
    }

    public void Dispose()
    {
        if (_disposed)
        {
            return;
        }

        _disposed = true;
        AppDomain.CurrentDomain.UnhandledException -= OnUnhandledException;
        TaskScheduler.UnobservedTaskException -= OnUnobservedTaskException;
    }

    private void OnUnhandledException(object sender, UnhandledExceptionEventArgs eventArgs)
    {
        if (eventArgs.ExceptionObject is Exception exception)
        {
            RecordCrash("XDM-CRASH-UNHANDLED", exception);
        }
        else
        {
            _diagnostics.Record(
                DiagnosticSeverity.Error,
                "XDM-CRASH-UNHANDLED",
                $"Unhandled non-exception crash object: {SecretRedactor.Redact(eventArgs.ExceptionObject?.ToString() ?? "unknown")}");
        }
    }

    private void OnUnobservedTaskException(object? sender, UnobservedTaskExceptionEventArgs eventArgs)
    {
        RecordCrash("XDM-CRASH-UNOBSERVED-TASK", eventArgs.Exception);
        eventArgs.SetObserved();
    }

    private void RecordCrash(string code, Exception exception)
    {
        _diagnostics.Record(
            DiagnosticSeverity.Error,
            code,
            SecretRedactor.Redact(exception.ToString()),
            new Dictionary<string, string?>(StringComparer.Ordinal)
            {
                ["exceptionType"] = exception.GetType().FullName,
                ["message"] = SecretRedactor.Redact(exception.Message)
            });
    }
}
