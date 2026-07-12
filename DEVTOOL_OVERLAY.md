# XDM Overlay — Downloads workflow and Settings categories

This overlay implements Phase 3 on top of commit `10a012e`.

## Downloads workflow

- replaces the always-visible action wall with a compact search and filter bar;
- shows bulk operations only while downloads are checked;
- tracks and announces the current bulk-selection count;
- adds a resizable list/details split using `GridSplitter`;
- keeps pause, resume, retry, and cancel immediately available for the selected download;
- groups priority, queue-order, conversion, removal, file, source, relocation, history-transfer, and timeline operations inside the details pane;
- preserves the virtualized list and all 32 existing command and picker bindings.

## Settings workflow

- groups the existing settings into General, Network, aria2, Credentials, and Data and updates categories;
- gives each category its own vertical scrolling surface;
- keeps a persistent save footer visible while switching categories;
- preserves all existing setting bindings, commands, and picker handlers.

## Qualification

The overlay adds source-level workflow architecture tests that require contextual bulk actions, a resizable details pane, five Settings categories, and the persistent save bar.

It does not change download-engine behavior, persistence formats, navigation sections, or parity evidence.
