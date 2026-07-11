namespace XDM.Platform;

public interface IPlatformInfo
{
    string OperatingSystem { get; }

    string Architecture { get; }

    string Runtime { get; }

    string DisplayName { get; }
}
