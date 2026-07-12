# XDM Overlay — Optional authenticated aria2 backend

This overlay adds an opt-in aria2 JSON-RPC backend to the active modern XDM application.

It includes:

- managed local `aria2c` and external RPC connection modes;
- RPC-secret authentication and secure remote-endpoint validation;
- settings persistence, legacy import, and secret-redacted export;
- managed-process lifecycle and session persistence;
- add, monitor, pause, resume, and remove task operations;
- an aria2 configuration and task panel inside the existing Settings section;
- core and download-engine qualification tests;
- enhancement documentation in `docs/parity/ARIA2-INTEGRATION.md`;
- an explicit clean parity-manifest replacement that removes any stale `download.aria2-backend` entry;
- a bounded PAC-condition regex that avoids the observed parser timeout;
- a zero-warning `Aria2RpcClient.ParseTasks` implementation.

aria2 is a new optional enhancement and has no upstream legacy evidence. It is therefore deliberately excluded from the legacy parity ledger.

Validation is limited to `app/XDM/XDM.Modern.sln`. Legacy WPF, GTK, WinForms, CoreFx, and MSIX projects are not included or built.
