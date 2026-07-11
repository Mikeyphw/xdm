namespace XDM.Core.Abstractions;

public interface IUiDispatcher
{
    bool CheckAccess();

    void Post(Action action);

    Task InvokeAsync(Func<Task> action);
}
