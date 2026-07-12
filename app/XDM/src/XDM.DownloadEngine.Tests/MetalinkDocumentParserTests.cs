using System.Text;

namespace XDM.DownloadEngine.Tests;

public sealed class MetalinkDocumentParserTests
{
    [Fact]
    public void ParsesOrderedMirrorsAndStrongestSupportedChecksum()
    {
        const string xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <metalink xmlns="urn:ietf:params:xml:ns:metalink">
              <file name="archive.zip">
                <size>12345</size>
                <hash type="sha-256">aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa</hash>
                <hash type="sha-512">bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb</hash>
                <url priority="2">https://mirror.example.test/archive.zip</url>
                <url priority="1">https://primary.example.test/archive.zip</url>
              </file>
            </metalink>
            """;
        using MemoryStream stream = new(Encoding.UTF8.GetBytes(xml));

        MetalinkFileEntry entry = Assert.Single(MetalinkDocumentParser.Parse(stream));

        Assert.Equal("archive.zip", entry.FileName);
        Assert.Equal(12345L, entry.Size);
        Assert.Equal("primary.example.test", entry.Sources[0].Host);
        Assert.Equal("mirror.example.test", entry.Sources[1].Host);
        Assert.Equal(DownloadChecksumService.Sha512, entry.ChecksumAlgorithm);
        Assert.Equal(new string('B', 128), entry.Checksum);
    }


    [Fact]
    public void RejectsDocumentsWithDocumentTypeDefinitions()
    {
        const string xml = "<!DOCTYPE metalink [<!ENTITY payload SYSTEM 'file:///etc/passwd'>]><metalink xmlns='urn:ietf:params:xml:ns:metalink'>&payload;</metalink>";
        using MemoryStream stream = new(Encoding.UTF8.GetBytes(xml));

        InvalidDataException exception = Assert.Throws<InvalidDataException>(
            () => MetalinkDocumentParser.Parse(stream));

        Assert.Contains("prohibited", exception.Message, StringComparison.OrdinalIgnoreCase);
    }

    [Fact]
    public void RejectsNonMetalinkDocument()
    {
        using MemoryStream stream = new(Encoding.UTF8.GetBytes("<html/>"));

        Assert.Throws<InvalidDataException>(() => MetalinkDocumentParser.Parse(stream));
    }
}
