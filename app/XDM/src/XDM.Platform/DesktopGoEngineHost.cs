using System.Runtime.InteropServices;
using System.Text.Json;

namespace XDM.Platform;

public sealed class DesktopGoFrameEventArgs(string kind, JsonElement payload) : EventArgs
{
    public string Kind { get; } = kind;
    public JsonElement Payload { get; } = payload;
}

public sealed class DesktopGoEngineHost : IAsyncDisposable
{
    private const int Ok = 0;
    private const int TimeoutStatus = 4;
    private readonly DesktopGoPlatformDispatcher _dispatcher;
    private readonly string _statePath;
    private CancellationTokenSource? _pumpCancellation;
    private Task? _pumpTask;
    private ulong _handle;
    private bool _started;

    public DesktopGoEngineHost(DesktopGoPlatformDispatcher dispatcher)
    {
        _dispatcher = dispatcher ?? throw new ArgumentNullException(nameof(dispatcher));
        _statePath = Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
            "xdm-modern",
            "go-desktop-state-v1.json");
        _dispatcher.WakeDue += OnWakeDue;
    }

    public event EventHandler<DesktopGoFrameEventArgs>? FrameReceived;

    public bool IsStarted => _started;

    public Task StartAsync(CancellationToken cancellationToken = default)
    {
        cancellationToken.ThrowIfCancellationRequested();
        if (_started)
        {
            return Task.CompletedTask;
        }
        Directory.CreateDirectory(Path.GetDirectoryName(_statePath)!);
        byte[] config = JsonSerializer.SerializeToUtf8Bytes(new
        {
            protocol = new { major = 1, minor = 0 },
            state_path = _statePath
        });
        EnsureSuccess(Native.xdm_engine_create(config, (nuint)config.Length, out _handle), "create");
        try
        {
            ValidateMetadata();
        }
        catch (InvalidOperationException)
        {
            AbortFailedStart();
            throw;
        }
        catch (JsonException)
        {
            AbortFailedStart();
            throw;
        }
        catch (KeyNotFoundException)
        {
            AbortFailedStart();
            throw;
        }
        catch (FormatException)
        {
            AbortFailedStart();
            throw;
        }
        _started = true;
        _pumpCancellation = new CancellationTokenSource();
        _pumpTask = Task.Run(() => PumpAsync(_pumpCancellation.Token), CancellationToken.None);
        return Task.CompletedTask;
    }

    public Task SubmitCommandAsync(string kind, JsonElement payload, CancellationToken cancellationToken = default)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(kind);
        cancellationToken.ThrowIfCancellationRequested();
        if (!_started)
        {
            throw new InvalidOperationException("The desktop Go engine is not started.");
        }
        string token = Guid.NewGuid().ToString("N");
        byte[] command = JsonSerializer.SerializeToUtf8Bytes(new
        {
            protocol = new { major = 1, minor = 0 },
            command_id = "cmd_" + token,
            operation_id = "op_" + token,
            kind,
            payload
        });
        EnsureSuccess(Native.xdm_engine_command(_handle, command, (nuint)command.Length), "command");
        return Task.CompletedTask;
    }

    private async Task PumpAsync(CancellationToken cancellationToken)
    {
        while (!cancellationToken.IsCancellationRequested && _started)
        {
            int status = Native.xdm_engine_next_frame(_handle, 250, out NativeBuffer buffer);
            if (status == TimeoutStatus)
            {
                continue;
            }
            EnsureSuccess(status, "next_frame");
            byte[] bytes = CopyAndFree(buffer);
            using JsonDocument document = JsonDocument.Parse(bytes);
            JsonElement root = document.RootElement;
            string kind = root.GetProperty("kind").GetString() ?? string.Empty;
            JsonElement payload = root.TryGetProperty("payload", out JsonElement body)
                ? body.Clone()
                : JsonSerializer.SerializeToElement(new { });
            if (kind == "platform.request")
            {
                await DispatchPlatformRequestAsync(payload, cancellationToken).ConfigureAwait(false);
                continue;
            }
            FrameReceived?.Invoke(this, new DesktopGoFrameEventArgs(kind, payload));
        }
    }

    private async Task DispatchPlatformRequestAsync(JsonElement request, CancellationToken cancellationToken)
    {
        ulong requestId = request.GetProperty("request_id").GetUInt64();
        ulong session = request.GetProperty("session").GetUInt64();
        object envelope;
        try
        {
            JsonElement payload = await _dispatcher.DispatchAsync(request, cancellationToken).ConfigureAwait(false);
            envelope = new
            {
                protocol = new { major = 1, minor = 0 },
                request_id = requestId,
                session,
                ok = true,
                payload
            };
        }
        catch (Exception exception) when (exception is IOException
            or UnauthorizedAccessException
            or InvalidDataException
            or InvalidOperationException
            or NotSupportedException
            or KeyNotFoundException)
        {
            envelope = new
            {
                protocol = new { major = 1, minor = 0 },
                request_id = requestId,
                session,
                ok = false,
                error_code = MapErrorCode(exception)
            };
        }
        byte[] reply = JsonSerializer.SerializeToUtf8Bytes(envelope);
        EnsureSuccess(Native.xdm_engine_platform_reply(_handle, reply, (nuint)reply.Length), "platform_reply");
    }

    private async void OnWakeDue(object? sender, DesktopWakeupRegistration registration)
    {
        if (!_started)
        {
            return;
        }
        try
        {
            JsonElement payload = JsonSerializer.SerializeToElement(new
            {
                wake_id = registration.WakeId,
                deadline_ms = registration.DeadlineMs,
                reason = registration.Reason
            });
            await SubmitCommandAsync("desktop.wakeup_due", payload).ConfigureAwait(false);
        }
        catch (InvalidOperationException)
        {
            // Shutdown raced the host wake callback; the next process start owns recovery.
        }
    }

    private void AbortFailedStart()
    {
        if (_handle != 0)
        {
            _ = Native.xdm_engine_shutdown(_handle);
            _handle = 0;
        }
    }

    private void ValidateMetadata()
    {
        EnsureSuccess(Native.xdm_engine_metadata(_handle, out NativeBuffer buffer), "metadata");
        byte[] bytes = CopyAndFree(buffer);
        using JsonDocument document = JsonDocument.Parse(bytes);
        JsonElement root = document.RootElement;
        int abiMajor = root.GetProperty("abi_major").GetInt32();
        int protocolMajor = root.GetProperty("protocol").GetProperty("major").GetInt32();
        if (abiMajor != 1 || protocolMajor != 1)
        {
            throw new InvalidOperationException($"Unsupported xdmcore ABI/protocol: abi={abiMajor}, protocol={protocolMajor}.");
        }
    }

    public async Task StopAsync()
    {
        if (!_started)
        {
            return;
        }
        _started = false;
        _pumpCancellation?.Cancel();
        if (_pumpTask is not null)
        {
            try
            {
                await _pumpTask.ConfigureAwait(false);
            }
            catch (OperationCanceledException)
            {
            }
        }
        EnsureSuccess(Native.xdm_engine_shutdown(_handle), "shutdown");
        _handle = 0;
        _pumpCancellation?.Dispose();
        _pumpCancellation = null;
        _pumpTask = null;
    }

    public async ValueTask DisposeAsync()
    {
        _dispatcher.WakeDue -= OnWakeDue;
        await StopAsync().ConfigureAwait(false);
        GC.SuppressFinalize(this);
    }

    private static string MapErrorCode(Exception exception) => exception switch
    {
        FileNotFoundException => "not_found",
        KeyNotFoundException => "secret_not_found",
        UnauthorizedAccessException => "permission_denied",
        NotSupportedException => "unsupported",
        InvalidDataException => "invalid_request",
        _ => "platform_error"
    };

    private static byte[] CopyAndFree(NativeBuffer buffer)
    {
        try
        {
            int length = checked((int)buffer.Length);
            byte[] bytes = new byte[length];
            if (length > 0)
            {
                Marshal.Copy(buffer.Data, bytes, 0, length);
            }
            return bytes;
        }
        finally
        {
            Native.xdm_buffer_free(buffer);
        }
    }

    private static void EnsureSuccess(int status, string operation)
    {
        if (status != Ok)
        {
            throw new InvalidOperationException($"xdmcore {operation} failed with status {status}.");
        }
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct NativeBuffer
    {
        public IntPtr Data;
        public nuint Length;
        public ulong Token;
    }

    private static class Native
    {
        private const string LibraryName = "xdmcore";

        [DllImport(LibraryName, CallingConvention = CallingConvention.Cdecl)]
        internal static extern int xdm_engine_create(byte[] config, nuint configLen, out ulong handle);

        [DllImport(LibraryName, CallingConvention = CallingConvention.Cdecl)]
        internal static extern int xdm_engine_command(ulong handle, byte[] data, nuint dataLen);

        [DllImport(LibraryName, CallingConvention = CallingConvention.Cdecl)]
        internal static extern int xdm_engine_next_frame(ulong handle, int timeoutMs, out NativeBuffer buffer);

        [DllImport(LibraryName, CallingConvention = CallingConvention.Cdecl)]
        internal static extern int xdm_engine_platform_reply(ulong handle, byte[] data, nuint dataLen);

        [DllImport(LibraryName, CallingConvention = CallingConvention.Cdecl)]
        internal static extern int xdm_engine_metadata(ulong handle, out NativeBuffer buffer);

        [DllImport(LibraryName, CallingConvention = CallingConvention.Cdecl)]
        internal static extern int xdm_engine_shutdown(ulong handle);

        [DllImport(LibraryName, CallingConvention = CallingConvention.Cdecl)]
        internal static extern void xdm_buffer_free(NativeBuffer buffer);
    }
}
