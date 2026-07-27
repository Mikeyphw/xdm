# IronFox installation and custom-scheme setup

## Generate the XPI

Use XDM Settings -> Browser extension to select an export folder and generate an XPI, or use the Gradle release tasks documented in `README.md`.

## Install in IronFox

1. Open IronFox Settings -> About IronFox.
2. Tap the application logo repeatedly until extension developer options are exposed.
3. Return to Settings and choose the option to install an extension from a file.
4. Select the generated XPI.
5. Reload any media page that was already open.

The exact menu wording may differ by IronFox release. The XDM Settings screen provides a copyable variant-specific checklist.

## External app handling

Set IronFox Settings -> Open links in apps to Always or Ask.

For the release app, these Boolean `about:config` values must remain enabled:

```text
network.protocol-handler.expose-all = true
network.protocol-handler.expose.xdmdownload = true
network.protocol-handler.external-default = true
network.protocol-handler.external.xdmdownload = true
```

Beta uses `xdmdownload-beta`; debug uses `xdmdownload-debug`. The extension-generated configuration and XDM package variant must match.

The extension popup never launches a custom scheme directly. It places a real anchor inside the current webpage, and the user taps the themed FAB there.
