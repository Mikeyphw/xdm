package com.mikeyphw.xdm.android.browser

sealed interface XdmBrowserDeepLinkParseResult {
    data object NotApplicable : XdmBrowserDeepLinkParseResult
    data class Accepted(val payload: XdmBrowserDeepLinkPayload) : XdmBrowserDeepLinkParseResult
    data class Rejected(val reason: XdmBrowserDeepLinkRejection) : XdmBrowserDeepLinkParseResult
}

enum class XdmBrowserDeepLinkRejection(
    val code: String,
    val userMessage: String,
) {
    PayloadTooLarge("payload-too-large", "The browser handoff exceeded XDM's safe size limit."),
    InvalidExpectedScheme("invalid-app-scheme", "This XDM build has an invalid browser scheme configuration."),
    MalformedUri("malformed-uri", "The browser handoff URI was malformed."),
    VariantMismatch("variant-mismatch", "The extension targets a different XDM app variant."),
    UnsafeEnvelope("unsafe-envelope", "The browser handoff used unsupported URI credentials, path, port, or fragment data."),
    UnsupportedAction("unsupported-action", "The browser handoff action is not supported."),
    MalformedQuery("malformed-query", "The browser handoff query could not be decoded safely."),
    UnsupportedContract("unsupported-contract", "The extension and app use different browser-bridge contract versions."),
    MissingMediaUrl("missing-media-url", "The browser handoff did not include a media URL."),
    UnsafeMediaUrl("unsafe-media-url", "The browser handoff media URL was rejected by XDM's external URL policy."),
    UnsafePageUrl("unsafe-page-url", "The browser handoff page URL was rejected by XDM's external URL policy."),
}
