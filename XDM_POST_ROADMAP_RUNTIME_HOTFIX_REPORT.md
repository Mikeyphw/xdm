# XDM Android post-roadmap runtime hotfix v4

Hotfix 1 of 1, validation-closure revision.

V4 preserves the runtime fixes from v2/v3 and closes the retained contract failures exposed by full `:app:testDebugUnitTest` validation:

- preserves the RM04 support-bundle distinction between runtime privacy and final export attestation and updates the stale Phase63 source-contract expectation to the RM04 test name;
- preserves the exact durable request-security contract, including the historical `InetAddress.getAllByName(host)` system resolver, while retaining Android active-network DNS as the primary resolver for HLS;
- explicitly updates the older REM04/05 launch-policy contract to the post-roadmap user-visible foreground-service policy required to restore ongoing transfer notifications;
- preserves RM02 truthful destination-card semantics (`destinationCardLabel(download)`) and updates stale UX03 source-contract expectations instead of regressing the UI back to the older raw destination label;
- removes the nullable `user.dir` warning from the new hotfix contract test.

The runtime scope remains: HLS exact-total discovery without mirroring downloaded bytes, active-network DNS with safety pinning, partial HLS recovery semantics, ongoing transfer notifications, Activity item removal/Clear activity, concise Recovery cards, and improved aria2 early-exit diagnostics.
