using System.Text;

namespace XDM.Media;

internal static class BoundedTextCapture
{
    internal const string TruncationNotice = "[... earlier output truncated by XDM ...]";

    public static async Task<string> ReadRetainedAsync(
        StreamReader reader,
        int maximumRetainedBytes)
    {
        ArgumentNullException.ThrowIfNull(reader);
        ArgumentOutOfRangeException.ThrowIfLessThan(maximumRetainedBytes, 1024);

        char[] buffer = new char[4096];
        StringBuilder builder = new();
        bool truncated = false;
        while (true)
        {
            int count = await reader.ReadAsync(buffer.AsMemory()).ConfigureAwait(false);
            if (count == 0)
            {
                string retained = builder.ToString();
                return truncated && retained.Length > 0
                    ? $"{TruncationNotice}{Environment.NewLine}{retained}"
                    : retained;
            }

            builder.Append(buffer, 0, count);
            TrimToByteLimit(builder, maximumRetainedBytes, ref truncated);
        }
    }

    private static void TrimToByteLimit(
        StringBuilder builder,
        int maximumRetainedBytes,
        ref bool truncated)
    {
        while (builder.Length > 0
            && Encoding.UTF8.GetByteCount(builder.ToString()) > maximumRetainedBytes)
        {
            int removeCharacters = Math.Max(1, Math.Min(builder.Length, builder.Length / 4));
            builder.Remove(0, removeCharacters);
            truncated = true;
        }
    }
}
