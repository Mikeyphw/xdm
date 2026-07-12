using System.Xml;
using System.Xml.Linq;

namespace XDM.DownloadEngine;

public static class MetalinkDocumentParser
{
    private const int MaximumFiles = 10_000;
    private const int MaximumSourcesPerFile = 64;
    private const long MaximumDocumentCharacters = 8L * 1024 * 1024;

    public static IReadOnlyList<MetalinkFileEntry> Parse(Stream stream)
    {
        ArgumentNullException.ThrowIfNull(stream);
        XmlReaderSettings settings = new()
        {
            DtdProcessing = DtdProcessing.Prohibit,
            XmlResolver = null,
            MaxCharactersInDocument = MaximumDocumentCharacters,
            IgnoreComments = true,
            IgnoreProcessingInstructions = true
        };
        XDocument document;
        try
        {
            using XmlReader reader = XmlReader.Create(stream, settings);
            document = XDocument.Load(reader, LoadOptions.None);
        }
        catch (XmlException exception)
        {
            throw new InvalidDataException("The Metalink XML is invalid or uses prohibited document features.", exception);
        }

        XElement root = document.Root ?? throw new InvalidDataException("The Metalink document has no root element.");
        XNamespace ns = root.Name.Namespace;
        if (!string.Equals(root.Name.LocalName, "metalink", StringComparison.OrdinalIgnoreCase))
        {
            throw new InvalidDataException("The document is not a Metalink document.");
        }

        List<MetalinkFileEntry> entries = [];
        foreach (XElement file in root.Elements(ns + "file").Take(MaximumFiles))
        {
            string? name = file.Attribute("name")?.Value;
            if (string.IsNullOrWhiteSpace(name))
            {
                continue;
            }

            long? size = long.TryParse(
                file.Element(ns + "size")?.Value,
                System.Globalization.NumberStyles.Integer,
                System.Globalization.CultureInfo.InvariantCulture,
                out long parsedSize)
                    && parsedSize > 0
                        ? parsedSize
                        : null;

            List<(int Priority, Uri Uri)> sources = [];
            foreach (XElement url in file.Elements(ns + "url").Take(MaximumSourcesPerFile))
            {
                if (!Uri.TryCreate(url.Value.Trim(), UriKind.Absolute, out Uri? uri)
                    || uri.Scheme is not ("http" or "https" or "ftp" or "ftps"))
                {
                    continue;
                }

                int priority = int.TryParse(
                    url.Attribute("priority")?.Value,
                    System.Globalization.NumberStyles.Integer,
                    System.Globalization.CultureInfo.InvariantCulture,
                    out int parsedPriority)
                        ? parsedPriority
                        : int.MaxValue;
                sources.Add((priority, uri));
            }

            if (sources.Count == 0)
            {
                continue;
            }

            XElement? hash = file.Elements(ns + "hash")
                .FirstOrDefault(element =>
                    string.Equals(element.Attribute("type")?.Value, "sha-512", StringComparison.OrdinalIgnoreCase))
                ?? file.Elements(ns + "hash")
                    .FirstOrDefault(element =>
                        string.Equals(element.Attribute("type")?.Value, "sha-256", StringComparison.OrdinalIgnoreCase));
            string? algorithm = hash?.Attribute("type")?.Value;
            string? checksum = hash?.Value.Trim();
            if (!string.IsNullOrWhiteSpace(algorithm) && !string.IsNullOrWhiteSpace(checksum))
            {
                algorithm = DownloadChecksumService.NormalizeAlgorithm(algorithm);
                checksum = DownloadChecksumService.NormalizeChecksum(checksum, algorithm);
            }

            Uri[] orderedSources = sources
                .OrderBy(static source => source.Priority)
                .Select(static source => source.Uri)
                .Distinct()
                .ToArray();
            entries.Add(new MetalinkFileEntry(name, size, orderedSources, algorithm, checksum));
        }

        return entries;
    }
}
