# Phase 46 — Media batch intake

Phase 46 adds a review-first batch intake path to the Media screen. It accepts pasted URLs, page text, HTML, or JSON and extracts HTTP(S) candidates without executing JavaScript or fetching remote pages; it does not execute JavaScript in any pasted page context.

## Scope

- Add `MediaBatchInputParser` and `MediaBatchIntakePlanner` in the media module.
- Accept LF and CRLF input.
- Ignore blank lines.
- Extract URLs from one-per-line input and pasted page/source text.
- Reject unsafe schemes and lines without supported URLs.
- Deduplicate normalized HTTP(S) URLs while preserving signed query strings.
- Cap total input size and URL count.
- Return a summary with accepted, duplicate, invalid, and page-inspection counts.
- Add a Media screen batch panel with `Inspect all`, `Add selected`, `Clear invalid`, and `Copy rejected lines` affordances.

## Deliberate boundary

Phase 46 does not implement the shared 1DM+-style sniffing engine and does not perform network probes. URLs classified as direct media become `MediaCaptureRecord` entries. Watch/page URLs are counted as needing page inspection so Phase 47 can handle them through the shared sniffing engine.

## Privacy

The batch parser never copies cookies, authorization headers, post bodies, or browser credentials. It only stores reviewable media capture records already visible in the pasted text.
