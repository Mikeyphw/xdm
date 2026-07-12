using System.Reflection;

namespace XDM.Core.Product;

public static class ProductVersion
{
    public static string Current { get; } = ResolveCurrent();

    private static string ResolveCurrent()
    {
        string? informational = typeof(ProductVersion).Assembly
            .GetCustomAttribute<AssemblyInformationalVersionAttribute>()?
            .InformationalVersion;
        if (!string.IsNullOrWhiteSpace(informational))
        {
            int metadata = informational.IndexOf('+', StringComparison.Ordinal);
            return metadata >= 0 ? informational[..metadata] : informational;
        }

        return typeof(ProductVersion).Assembly.GetName().Version?.ToString(3) ?? "0.0.0";
    }
}
