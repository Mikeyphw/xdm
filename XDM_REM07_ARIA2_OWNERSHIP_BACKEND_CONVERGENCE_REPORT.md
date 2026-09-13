# XDM Desktop REM07 — aria2 ownership, RPC state, queue policy, and backend convergence

## Scope

REM07 closes the frozen S04 ownership set: **17 findings — 6 High / 10 Medium / 1 Low**. It depends on the transactional persistence, network/settings, and native-transfer integrity foundations delivered by REM03, REM04, and REM06.

Owned findings: S04-01 through S04-17.

## Implementation

### Complete RPC ownership and durable identity

- Waiting and stopped aria2 queues are now paged to exhaustion instead of truncating ownership views at the first 100 tasks.
- XDM reserves a deterministic 16-hex aria2 GID from a durable request identity before `aria2.addUri`, persists that ownership immediately, and rejects an RPC result that returns a different GID.
- Uncertain `addUri` responses reconcile the reserved GID directly with `tellStatus`; native fallback remains blocked until ownership is conclusively absent or reconciled.
- Restart/adoption no longer uses destination path alone: source identity, destination identity, and known length must agree. RPC task snapshots retain their source URI set for that comparison.
- The persisted download schema now carries the durable backend request identity needed to reconstruct ownership after restart.

### Health loss and truthful control state

- RPC/process health loss moves owned nonterminal aria2 sessions into recoverable failed state instead of leaving them apparently active or silently falling back to native.
- Pause, resume, remove, queue stop, policy stop, cancellation, and shutdown use confirmed RPC state rather than assuming that sending a command stopped the remote writer.
- If stop/pause cannot be confirmed, XDM retains the aria2 ownership/concurrency lease, marks recovery required, and will not report the task as stopped.
- A failed direct Pause clears the local pause request instead of leaving later control flow permanently latched.
- Polling and reconfiguration contain malformed JSON/RPC parsing failures and publish degraded health instead of faulting the polling task.

### Scheduler, queue, and transfer-policy convergence

- aria2 tasks now acquire the same queue/global/host concurrency leases used by native transfers. A remote `waiting` task is paused and releases its XDM lease before retrying, preventing waiting tasks from starving native work.
- Moving a live aria2 download between queues first obtains confirmed pause/ownership state, releases the previous runtime lease, mutates queue order transactionally, and resumes only under the destination queue policy.
- Effective smart/queue/request speed limits are pushed through `aria2.changeOption`, and settings/policy changes update owned aria2 tasks live.
- Per-download connection count is passed to `split` and `max-connection-per-server`; `ContinueDownloads` and certificate policy are also propagated rather than hard-coded.
- Automatic backend selection now performs a bounded metadata probe when total length is unknown, making size-based aria2 routing reachable for ordinary Add flows.

### Managed vs external RPC settings

- External RPC mode applies supported global concurrency through `aria2.changeGlobalOption`; managed-process-only executable/session/autostart/save-session/additional-argument fields are disabled in the UI when external mode is selected.
- Configuration/start commands report unavailable health as failure rather than claiming success.
- Settings UI numeric ranges now match `Aria2IntegrationSettings.Normalize()` limits.

## Regression coverage

REM07 adds or extends contracts for:

- paged ownership of more than 1000 waiting/stopped tasks;
- add options for deterministic GID, continue, initial pause, certificate policy, and per-download connection count;
- deterministic-GID mismatch rejection;
- RPC source provenance parsing;
- uncertain add response recovery without native fallback or duplicate writers;
- health loss while an owned task is active;
- direct pause failure truthfulness;
- existing destination-collision and persisted-ownership tests under the stronger ownership model.

## Validation policy

REM07 is an intermediate remediation overlay. It is packaged with Devtool schema-v2 atomic apply, clean-worktree enforcement, and automatic single-commit policy. Apply with `--no-validate`; full build/xUnit/release validation remains reserved for the final remediation seal.
