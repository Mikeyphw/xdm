using System.Net.Http.Headers;

namespace XDM.DownloadEngine;

internal sealed record TransferIdentity(
    long? ExpectedLength,
    string? EntityTag,
    DateTimeOffset? LastModified)
{
    public bool HasResumeValidator => HasStrongEntityTag(EntityTag) || LastModified is not null;

    public static bool HasStrongEntityTag(string? value)
        => !string.IsNullOrWhiteSpace(value)
            && EntityTagHeaderValue.TryParse(value, out EntityTagHeaderValue? parsed)
            && !parsed.IsWeak;

    public static TransferIdentity FromHttpResponse(HttpResponseMessage response, long? expectedLength = null)
        => new(
            response.Content.Headers.ContentRange?.Length
                ?? response.Content.Headers.ContentLength
                ?? expectedLength,
            response.Headers.ETag?.ToString(),
            response.Content.Headers.LastModified);

    public void ValidateResumeResponse(HttpResponseMessage response, bool requireValidator = true)
    {
        if (requireValidator && !HasResumeValidator)
        {
            throw new DownloadIntegrityException(
                "Resume requires a strong ETag or Last-Modified validator; existing bytes cannot be trusted without remote identity proof.");
        }

        string? actualEntityTag = response.Headers.ETag?.ToString();
        if (HasStrongEntityTag(EntityTag))
        {
            if (string.IsNullOrWhiteSpace(actualEntityTag))
            {
                throw new DownloadIntegrityException("The server omitted the entity tag required to validate this resume.");
            }
            if (!string.Equals(EntityTag, actualEntityTag, StringComparison.Ordinal))
            {
                throw new DownloadIntegrityException("The remote file entity tag changed while resuming.");
            }
        }
        else if (!string.IsNullOrWhiteSpace(EntityTag) && LastModified is null)
        {
            throw new DownloadIntegrityException(
                "The stored entity tag is weak or invalid and cannot safely validate this resume.");
        }

        if (LastModified is DateTimeOffset expectedLastModified)
        {
            DateTimeOffset? actualLastModified = response.Content.Headers.LastModified;
            if (actualLastModified is null)
            {
                throw new DownloadIntegrityException("The server omitted the modification date required to validate this resume.");
            }
            if (actualLastModified.Value != expectedLastModified)
            {
                throw new DownloadIntegrityException("The remote file modification date changed while resuming.");
            }
        }
    }

    public bool Matches(TransferIdentity other)
    {
        ArgumentNullException.ThrowIfNull(other);
        if (ExpectedLength is long expected && other.ExpectedLength is long actual && expected != actual)
        {
            return false;
        }

        if (HasStrongEntityTag(EntityTag))
        {
            return HasStrongEntityTag(other.EntityTag)
                && string.Equals(EntityTag, other.EntityTag, StringComparison.Ordinal);
        }

        return LastModified is DateTimeOffset expectedLastModified
            && other.LastModified is DateTimeOffset actualLastModified
            && expectedLastModified == actualLastModified;
    }
}
