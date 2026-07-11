# Avalonia queue and scheduler runtime

This phase turns the persisted queue and scheduler definitions into active runtime behavior.

Implemented:

- persistent queue membership and ordering for every download;
- manual queue start and stop actions;
- more than one active queue at the same time;
- global and per-queue concurrency gates;
- per-download, per-queue, and global speed-limit precedence;
- queue movement and ordering controls in the Avalonia interface;
- timezone-aware scheduler evaluation at startup, every 15 seconds, and after settings changes;
- overnight schedule support through the existing core schedule model;
- immediate catch-up when XDM starts inside an active schedule window;
- queue metadata migration for existing download history;
- runtime and persistence tests.

Known transitional limits:

- changing queue concurrency after its runtime gate has been created requires restarting XDM;
- a download moved while actively transferring finishes its current transfer before the new queue policy fully applies;
- weekday selection remains represented by the core model but is not yet exposed as individual checkboxes in the UI.
