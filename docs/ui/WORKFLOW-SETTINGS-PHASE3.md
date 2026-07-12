# Workflow and settings UI phase 3

This phase reduces action density in Downloads and reorganizes Settings without changing download-engine or persistence behavior.

## Downloads

- Search and status filtering remain global.
- Bulk actions appear only while one or more checkboxes are selected.
- Selected-download actions live in the resizable details pane.
- Secondary priority, conversion, file, source, relocation, history-transfer, and timeline operations are grouped into expandable sections.
- The list remains virtualized and all existing commands remain bound.

## Settings

Settings are grouped into five categories:

1. General
2. Network
3. aria2
4. Credentials
5. Data and updates

Each category owns its scrolling surface. A persistent footer keeps the save action visible while navigating categories.

## Compatibility

The phase preserves all existing commands, storage pickers, localization bindings, automation identifiers, keyboard shortcuts, settings models, and parity evidence.
