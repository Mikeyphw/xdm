# XDM Overlay — Smart bandwidth and queue automation

This overlay applies on top of commit `fc33a2b`.

## Transfer profiles

- adds editable Balanced, Focus, Gaming, Metered, Battery saver, and Overnight profiles;
- each profile controls total concurrent downloads, concurrent downloads per host, and a per-transfer speed ceiling;
- combines the base profile, environment profile, and active schedule profiles using the most restrictive limits;
- applies profile and settings changes live;
- immediately wakes waiting transfers when limits increase;
- lets active transfers finish their current slots when limits decrease.

## Environment policy

- detects network availability through platform APIs;
- detects Linux battery discharge state through `/sys/class/power_supply`;
- supports Auto, Metered/Unmetered, and Battery/AC overrides in the UI;
- supports deterministic managed overrides through `XDM_NETWORK_METERED` and `XDM_ON_BATTERY`;
- can ignore an environment, apply a stricter profile, or pause transfers;
- automatically returns policy-paused transfers to their queues and resumes them when eligible.

## Queue automation

- adds acyclic queue dependencies;
- starting a queue recursively requests all prerequisites;
- dependent queues wait for prerequisite downloads to become terminal;
- successful-only mode blocks when a prerequisite fails or is cancelled;
- stopping a root queue releases prerequisite queues that are no longer needed;
- exposes requested, active, and blocked queue state in the UI;
- keeps global, per-queue, and per-host concurrency live without restart.

## Scheduling

- lets every schedule select an optional bandwidth profile;
- keeps that profile active for the schedule window and its tracked run;
- preserves existing missed-run and completion-action behavior.

## Settings and migration

- advances settings schema to version 7;
- migrates older settings to the default smart-transfer profiles;
- normalizes invalid profile references and circular queue dependencies;
- preserves new profile and dependency settings through the existing settings import/export path.

## Validation

Devtool must restore, build, and test only `app/XDM/XDM.Modern.sln`. The build must have zero warnings and zero errors. The parity feature manifest is unchanged.
