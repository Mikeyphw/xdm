# Retired Firefox desktop fork

XFE01 retired this directory as executable extension source.

The only supported Firefox extension implementation is the proven Android-owned source at:

`app/XDM.Android/browser-extension/src/main/extension/xdm-firefox`

Desktop packages render that exact source into `XDM-Firefox.xpi` and adapt XDM Desktop to its existing `xdmdownload://add` v1 / `xdmdownload://capture` v3 handoff contract. Do not add Firefox capture logic here.
