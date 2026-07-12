# Upstream parity corrections — Overlay 22

Overlay 22 re-audits the modern application against the original XDM public
feature contract instead of treating the Overlay 21 ledger as the complete
inventory.

## Implemented upstream capabilities

### FTP and FTPS

The download engine now accepts `ftp://` and `ftps://` sources with GET
semantics. The native client supports passive EPSV/PASV transfers, USER/PASS
authentication, anonymous fallback, SIZE and MDTM probes, REST resume, explicit
TLS and implicit TLS on port 990, protected data channels, cancellation,
progress publication, partial-file recovery and the same finalization path used
by HTTP downloads.

Credentials and paths are validated before being placed on the FTP control
channel. Normal certificate-chain and hostname validation remain enabled.

### PAC and enterprise proxy authentication

Network settings now include direct, system, manual and automatic-script modes.
PAC files are limited to 1 MiB and may be loaded only from HTTP, HTTPS or local
file URLs. The evaluator intentionally implements the deterministic PAC rules
needed by the original workflow (`isPlainHostName`, `dnsDomainIs`,
`shExpMatch`, ordered proxy directives and `DIRECT`) without hosting arbitrary
JavaScript.

Basic proxy credentials are supported explicitly. Integrated mode supplies the
signed-in operating-system credentials to the platform HTTP stack, which is the
supported path for Windows ISA, NTLM and Kerberos negotiation.

### Device conversion profiles

The conversion catalog now contains 120 fixed profiles spanning phones,
tablets, streaming devices, consoles and televisions. Profiles are generated
from a bounded family/quality matrix and can only select fixed FFmpeg arguments;
they cannot inject commands or arbitrary switches.

### Verified in-application updates

The settings workflow can check the official HTTPS release manifest and stage a
package for the current Linux or Windows runtime identifier. Manifest and
package sizes are bounded, download hosts and extensions are allowlisted, and
the exact declared size and SHA-256 must match before an atomic rename. XDM does
not execute the package automatically; the user opens the staged package folder
and retains control of installation.

## Explicitly out of scope

### macOS

The maintained modernization target is Linux and Windows. macOS is deliberately
not restored, built or qualified, matching the project owner's stated scope.
This is recorded as `notApplicable`, not counted as implemented parity.

### Adobe HDS/F4M

The original README advertises Adobe HDS. The retained upstream media-parser
source used for this port contains DASH, HLS and YouTube parsers but no working
HDS/F4M parser to port or qualify. Because HDS is also an obsolete Flash-era
protocol, the modern project records the claim as source-unsubstantiated rather
than adding an untestable placeholder and calling it parity.

## CI closure

The packaged application bootstrap now validates all eight modern navigation
sections. The previous six-section assertion predated the conversion and
scheduler/settings expansion and caused otherwise healthy package-smoke runs to
fail. Linux and Windows continue to build, test, bootstrap and package only
`XDM.Modern.sln`.
