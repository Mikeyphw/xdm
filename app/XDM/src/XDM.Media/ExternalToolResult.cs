namespace XDM.Media;

public sealed record ExternalToolResult(int ExitCode, string StandardOutput, string StandardError)
{
    public bool Succeeded => ExitCode == 0;
}
