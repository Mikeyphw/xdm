using System.Net;
using System.Text;
using System.Text.Json;
using XDM.DownloadEngine.Aria2;

namespace XDM.DownloadEngine.Tests;

public sealed class Aria2RpcClientTests
{
    [Fact]
    public async Task GetVersionSendsSecretTokenAsFirstParameter()
    {
        RecordingHandler handler = new(request =>
        {
            Assert.Equal("aria2.getVersion", request.GetProperty("method").GetString());
            JsonElement parameters = request.GetProperty("params");
            Assert.Equal("token:top-secret", parameters[0].GetString());
            return """{"jsonrpc":"2.0","id":"1","result":{"version":"1.37.0"}}""";
        });
        Aria2RpcClient client = CreateClient(handler, "top-secret");

        string version = await client.GetVersionAsync();

        Assert.Equal("1.37.0", version);
        Assert.Equal(1, handler.RequestCount);
    }

    [Fact]
    public async Task AddUriMapsDestinationHeadersAuthenticationAndLimits()
    {
        RecordingHandler handler = new(request =>
        {
            Assert.Equal("aria2.addUri", request.GetProperty("method").GetString());
            JsonElement parameters = request.GetProperty("params");
            Assert.Equal("https://example.test/file.bin", parameters[0][0].GetString());
            JsonElement options = parameters[1];
            Assert.Equal("renamed.bin", options.GetProperty("out").GetString());
            Assert.Equal("8", options.GetProperty("split").GetString());
            Assert.Equal("alice", options.GetProperty("http-user").GetString());
            Assert.Equal("secret", options.GetProperty("http-passwd").GetString());
            Assert.Equal("X-Test: value", options.GetProperty("header")[0].GetString());
            Assert.Equal("2048", options.GetProperty("max-download-limit").GetString());
            return """{"jsonrpc":"2.0","id":"1","result":"2089b05ecca3d829"}""";
        });
        Aria2RpcClient client = CreateClient(handler);
        Aria2AddRequest request = new(
            new Uri("https://example.test/file.bin"),
            Path.GetTempPath(),
            "renamed.bin",
            new Dictionary<string, string> { ["X-Test"] = "value" },
            "alice",
            "secret",
            2048);

        string gid = await client.AddUriAsync(request, 8, 1024 * 1024);

        Assert.Equal("2089b05ecca3d829", gid);
    }

    [Fact]
    public async Task TellActiveParsesProgressPathSpeedAndCapabilities()
    {
        RecordingHandler handler = new(_ => """
            {
              "jsonrpc":"2.0",
              "id":"1",
              "result":[{
                "gid":"abc123",
                "status":"active",
                "totalLength":"1000",
                "completedLength":"250",
                "downloadSpeed":"125",
                "uploadSpeed":"0",
                "connections":"4",
                "dir":"/tmp",
                "files":[{"path":"/tmp/file.iso","uris":[{"uri":"https://example.test/file.iso","status":"used"}]}]
              }]
            }
            """);
        Aria2RpcClient client = CreateClient(handler);

        Aria2TaskSnapshot task = Assert.Single(await client.TellActiveAsync());

        Assert.Equal("abc123", task.Gid);
        Assert.Equal(Aria2TaskStatus.Active, task.Status);
        Assert.Equal("file.iso", task.DisplayName);
        Assert.Equal(0.25, task.ProgressFraction);
        Assert.Equal(125, task.DownloadSpeedBytesPerSecond);
        Assert.Equal(4, task.Connections);
        Assert.True(task.CanPause);
        Assert.False(task.CanResume);
    }

    [Fact]
    public async Task RpcErrorIsReportedWithCodeAndMessage()
    {
        RecordingHandler handler = new(_ => """
            {"jsonrpc":"2.0","id":"1","error":{"code":1,"message":"Unauthorized"}}
            """);
        Aria2RpcClient client = CreateClient(handler, "wrong-secret");

        Aria2RpcException exception = await Assert.ThrowsAsync<Aria2RpcException>(
            () => client.GetVersionAsync());

        Assert.Equal(1, exception.Code);
        Assert.Equal("Unauthorized", exception.RpcMessage);
    }

    [Fact]
    public async Task PauseSendsTaskIdentifierAfterAuthenticationToken()
    {
        RecordingHandler handler = new(request =>
        {
            Assert.Equal("aria2.pause", request.GetProperty("method").GetString());
            JsonElement parameters = request.GetProperty("params");
            Assert.Equal("token:secret", parameters[0].GetString());
            Assert.Equal("task-gid", parameters[1].GetString());
            return """{"jsonrpc":"2.0","id":"1","result":"task-gid"}""";
        });
        Aria2RpcClient client = CreateClient(handler, "secret");

        await client.PauseAsync("task-gid");

        Assert.Equal(1, handler.RequestCount);
    }

    private static Aria2RpcClient CreateClient(RecordingHandler handler, string secret = "")
        => new(new HttpClient(handler), new Uri("http://127.0.0.1:6800/jsonrpc"), secret);

    private sealed class RecordingHandler(Func<JsonElement, string> responseFactory) : HttpMessageHandler
    {
        public int RequestCount { get; private set; }

        protected override async Task<HttpResponseMessage> SendAsync(
            HttpRequestMessage request,
            CancellationToken cancellationToken)
        {
            RequestCount++;
            string requestJson = await request.Content!.ReadAsStringAsync(cancellationToken);
            using JsonDocument document = JsonDocument.Parse(requestJson);
            string responseJson = responseFactory(document.RootElement.Clone());
            return new HttpResponseMessage(HttpStatusCode.OK)
            {
                Content = new StringContent(responseJson, Encoding.UTF8, "application/json")
            };
        }
    }
}
