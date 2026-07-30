# Phase57 Runtime Failure Recovery UX

Phase57 turns failed download states into a review-first recovery card on download details.
It is a UX and planner layer only: it does not start transfers automatically, delete files,
persist browser-session values, add permissions, migrate Room, or reopen Debug Workbench.

## Recovery classes

The planner recognizes human-facing failure causes:

- server requires browser access
- browser session may be stale
- media inspection recommended
- storage visibility needs review
- recovery state needs review
- try another transfer method
- queue policy is holding this item
- retry needs review

## Actions

The card can guide users toward existing safe flows:

- refresh from browser
- retry with captured session
- try yt-dlp/media resolver
- try aria2 or XDM Native
- re-check storage visibility
- open Recovery Doctor
- copy a redacted recovery report

## Privacy boundary

The card shows only the source site and human labels. It must not render full URLs, Cookie
values, Authorization values, bearer tokens, credential query values, or raw header dumps.
The copied report is redacted and explicitly states that private values are hidden.
