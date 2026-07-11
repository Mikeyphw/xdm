using System.Buffers.Binary;

namespace XDM.BrowserIntegration;

public static class NativeMessageFraming
{
    public static async Task<byte[]?> ReadAsync(Stream input, CancellationToken cancellationToken = default)
    {
        ArgumentNullException.ThrowIfNull(input);
        byte[] lengthBytes = new byte[4];
        int firstRead = await input.ReadAsync(lengthBytes.AsMemory(0, 4), cancellationToken).ConfigureAwait(false);
        if (firstRead == 0)
        {
            return null;
        }

        if (firstRead < 4)
        {
            await input.ReadExactlyAsync(lengthBytes.AsMemory(firstRead, 4 - firstRead), cancellationToken)
                .ConfigureAwait(false);
        }

        int length = BinaryPrimitives.ReadInt32LittleEndian(lengthBytes);
        if (length is <= 0 or > BrowserNativeProtocol.MaximumMessageBytes)
        {
            throw new InvalidDataException("Native message size is invalid.");
        }

        byte[] payload = new byte[length];
        await input.ReadExactlyAsync(payload, cancellationToken).ConfigureAwait(false);
        return payload;
    }

    public static async Task WriteAsync(
        Stream output,
        ReadOnlyMemory<byte> payload,
        CancellationToken cancellationToken = default)
    {
        ArgumentNullException.ThrowIfNull(output);
        if (payload.Length is <= 0 or > BrowserNativeProtocol.MaximumMessageBytes)
        {
            throw new InvalidDataException("Native response size is invalid.");
        }

        byte[] length = new byte[4];
        BinaryPrimitives.WriteInt32LittleEndian(length, payload.Length);
        await output.WriteAsync(length, cancellationToken).ConfigureAwait(false);
        await output.WriteAsync(payload, cancellationToken).ConfigureAwait(false);
        await output.FlushAsync(cancellationToken).ConfigureAwait(false);
    }
}
