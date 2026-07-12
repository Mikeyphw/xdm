namespace XDM.DownloadEngine.Aria2;

public sealed class Aria2RpcException : Exception
{
    public Aria2RpcException(int code, string message)
        : base($"aria2 RPC error {code}: {message}")
    {
        Code = code;
        RpcMessage = message;
    }

    public int Code { get; }

    public string RpcMessage { get; }
}
