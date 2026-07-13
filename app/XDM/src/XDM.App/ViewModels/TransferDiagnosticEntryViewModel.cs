using XDM.Core.Diagnostics;

namespace XDM.App.ViewModels;

public sealed class TransferDiagnosticEntryViewModel
{
    public TransferDiagnosticEntryViewModel(TransferDiagnosticEvent item)
    {
        Timestamp = item.Timestamp.ToLocalTime().ToString("HH:mm:ss.fff", System.Globalization.CultureInfo.CurrentCulture);
        Stage = item.Stage.ToString();
        Severity = item.Severity.ToString();
        Code = item.Code;
        Message = item.Message;
        Details = item.Context.Count == 0
            ? string.Empty
            : string.Join(" · ", item.Context.Select(static pair => $"{pair.Key}: {pair.Value}"));
    }

    public string Timestamp { get; }

    public string Stage { get; }

    public string Severity { get; }

    public string Code { get; }

    public string Message { get; }

    public string Details { get; }
}
