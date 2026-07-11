using System.Buffers.Binary;
using System.Text;
using XDM.BrowserIntegration;

namespace XDM.BrowserMedia.Tests;

public sealed class NativeMessageFramingTests
{
    [Fact]
    public async Task RoundTripsNativeMessageFrame()
    {
        byte[] payload = Encoding.UTF8.GetBytes("{\"type\":\"fixture\"}");
        await using MemoryStream stream = new();

        await NativeMessageFraming.WriteAsync(stream, payload);
        stream.Position = 0;
        byte[]? result = await NativeMessageFraming.ReadAsync(stream);

        Assert.NotNull(result);
        Assert.Equal(payload, result);
    }

    [Fact]
    public async Task ReadsFragmentedLengthPrefix()
    {
        byte[] payload = Encoding.UTF8.GetBytes("fixture");
        byte[] frame = new byte[payload.Length + 4];
        BinaryPrimitives.WriteInt32LittleEndian(frame, payload.Length);
        payload.CopyTo(frame, 4);
        await using FragmentedReadStream stream = new(frame);

        byte[]? result = await NativeMessageFraming.ReadAsync(stream);

        Assert.NotNull(result);
        Assert.Equal(payload, result);
    }

    [Fact]
    public async Task RejectsOversizedLengthPrefix()
    {
        byte[] prefix = new byte[4];
        BinaryPrimitives.WriteInt32LittleEndian(prefix, BrowserNativeProtocol.MaximumMessageBytes + 1);
        await using MemoryStream stream = new(prefix);

        await Assert.ThrowsAsync<InvalidDataException>(() => NativeMessageFraming.ReadAsync(stream));
    }

    private sealed class FragmentedReadStream(byte[] data) : MemoryStream(data)
    {
        public override ValueTask<int> ReadAsync(
            Memory<byte> buffer,
            CancellationToken cancellationToken = default)
            => base.ReadAsync(buffer[..Math.Min(1, buffer.Length)], cancellationToken);
    }
}
