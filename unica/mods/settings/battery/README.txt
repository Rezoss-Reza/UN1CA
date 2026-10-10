UN1CA Battery Saving — built into the ROM
========================================
Entry: UN1CA Settings > Battery Saving, immediately below Font Selector.
No Magisk, KernelSU, su grant, module installation or phone-side setup command
is required. The ordinary ROM build includes the Settings patch, native worker,
init configuration, metadata and SELinux policy through the existing Settings
mod. Developer build.sh regenerates the checked-in patch and native executable
only when their source changes; ROM users never run it.

Controls and profiles
---------------------
The feature is disabled by default. The master switch applies/enables the current
profile or restores previous values. Sliders and presets are drafts until Save
and apply. Discrete controls use labelled steps. CPU/GPU/refresh choices come
from the device. Swappiness is 0..200; the UI's cached-app range is 32..4096.
Keep original removes the override and restores its saved original value.

Balanced preserves kernel/memory defaults, reports the actual freezer state,
and selects no apps. Efficiency picks the highest supported frequency no greater
than 85% of the original maximum on the fastest CPU cluster. Refresh-rate limits
are optional. NOSLEN reproduces the supplied module's intended Wi-Fi runtime PM
on, wakeup disabled, ASPM performance, swappiness 60, cached limit 32, and three
restricted Samsung apps. It uses the actual Android standby API.

Stingy is an explicitly aggressive preset. Selection is a draft; review its
warning and use Save and apply. It is not enabled automatically by a ROM update.
It selects only supported choices reported by this device:

- CPU: lowest advertised ceiling at or above each policy's live minimum.
  GPU: same rule, using devfreq minimum. Neither lowers minimum clocks or
  offlines cores. Missing minimum metadata means no automatic clock override.
- Refresh: lowest advertised display rate; lower min_refresh_rate too when the
  existing minimum exceeds the new ceiling, with a separate undo record.
- Wi-Fi runtime PM: auto; wakeup: disabled. ASPM: powersupersave, falling back to
  powersave only if advertised. Aggressive link sleep may expose driver faults.
- Android Battery Saver: on; cached-app freezer: enabled (may require reboot).
- Automatic account sync: off. Android sync adapters stop; apps' independent
  network mechanisms are handled by the restrictions below, not this switch.
- Data Saver: on. For automatically targeted apps, UID network policy adds
  REJECT_METERED_BACKGROUND (1), removes ALLOW_METERED_BACKGROUND (4), and
  preserves all other flags. This is not a block on all Wi-Fi traffic.
- Background: automatically target downloaded apps, launchable preinstalled
  apps, Play Store and Galaxy Store. Use restricted standby bucket 45, UID
  RUN_IN_BACKGROUND and RUN_ANY_IN_BACKGROUND app-ops MODE_IGNORED (1), and
  remove user-added Doze exemptions. UID app-op snapshots preserve the original
  UID mode, not an effective mode merged with per-package policy.
- Screen: adaptive brightness off, manual brightness 8 on the legacy 1..255
  setting scale (very dim, not a measured luminance percentage), 15-second
  timeout, AOD off, system dark mode. All three animation scales become 0.
- Always-available Wi-Fi/BLE scanning: off. Mobile data always active while on
  Wi-Fi: off. This does not switch off calls, foreground internet or Bluetooth.
- Cache limit and swappiness: unchanged; fewer cached apps or more reclaim can
  increase CPU/storage work and cannot establish maximum battery efficiency.

Automatic targeting protects Settings, SystemUI, Play services, phone services,
the default dialer, default launcher and enabled keyboards. Non-launchable
system services and all packages sharing a protected UID remain protected.
The manual app list is additional standby control, not an exception list.
Targets are rediscovered on apply and boot; reapply after installing new apps.

Expect missing notifications, failed background uploads/backups, app updates,
widgets, VPNs and wearable links, very slow apps and a barely visible screen.
There is no universal zero-background guarantee: essential Android services,
foreground services, system Doze exemptions and vendor policies remain. No
package disabling, forced Doze loop, persistent process killer or blanket
network shutdown is installed. Missed work is not recreated when switching off.
Minimum clocks can lengthen work enough to increase energy per task; matched
on-device consumption tests are required before claiming maximum battery life.

Disable Battery Saving or select Balanced and apply to restore saved originals.
New controls join the durable undo journal. All selected originals are captured
before writes to protect against Battery Saver side effects. Saver is restored
before dependent policies, failed restoration remains retryable, and UID policy
restoration refuses a changed package UID. Custom dark-mode schedule type is
saved and restored alongside night mode. Existing version-1 profiles still
load; older ROM builds cannot import the new Stingy controls.

Backups use Android's document picker and contain user choices only. Imports
are size/schema/range checked and start disabled. They do not contain commands,
file paths or rollback snapshots. Restrictions apply to the owner user. Settings,
SystemUI and Play services are protected; users must exclude other essential
messaging, alarm, VPN, wearable and Samsung feature dependencies themselves.
Doze changes affect user-added exemptions only.

ROM-native architecture
-----------------------
BatteryEngine runs inside Settings with its platform permissions. It directly
uses SettingsProvider, DeviceConfig, UsageStats and DeviceIdle APIs. The only
added manifest permission is the signature permission WRITE_DEVICE_CONFIG.
Profile and durable original-value journal live in Settings' private files.
BOOT_COMPLETED schedules a single JobService operation when a profile is enabled
or restoration remains pending. There is no continuously running Java service.

Kernel writes use /system/bin/unica-battery, a small native one-shot init worker.
Settings writes a bounded request to /data/misc/unica_battery/requests, then sets
sys.unica.battery.request. DAC plus SELinux restrict the request/response channel
to system Settings and the worker. UUID responses and retry tokens handle boot
races; flock serializes worker execution. The native parser accepts only list,
get or set with a hard-coded control identifier and supported value. It never
accepts arbitrary paths or executes commands.

The worker runs in a dedicated SELinux domain with an empty Linux capability
set. It has no Binder, shell-execution, execmem or network grants. Three generic
Wi-Fi/PCIe files receive dedicated labels, preserving existing consumers. CPU,
Adreno and swappiness access uses their existing firmware types. Android-level
privileges stay in Settings. The installer updates the policy identifier to
prevent stale vendor/odm precompiled policy from hiding the new domain.

Original values are journaled before writes; failure retains the undo record.
Disable restores Android settings and same-boot kernel values. Old-boot kernel
snapshots are discarded because those values reset at reboot. Vendor power
management may subsequently override tunables; the implementation reports
readback failures rather than continuously fighting it. Battery gains still
require matched consumption tests.

Build and checks (developer only)
--------------------------------
    unica/mods/settings/battery/build.sh
    unica/mods/settings/battery/tests/build.sh
    git diff --check

The build uses Android SDK 37 / NDK 28.2, generates an additive smali patch and
native executable, and backs up prior patches. Native source is native/kernel.cpp.
The normal ROM mod applies its supplied artifacts without requiring SDK/NDK.

Validation
----------
The original 40 Java profile/rollback assertions and 16 native allowlist assertions passed on
Android. The earlier direct ceiling check accepted 2841600 kHz on policy7 and
immediately restored 3360000 kHz; no permanent profile was enabled.
SELinux compilation passed in Android init's runtime mode for both untouched
firmware and the current customized ROM policy. Strict neverallow comparison
showed the same 226 pre-existing mixed-version vendor conflicts as the untouched
baseline, with zero new worker conflicts. The installer is idempotent.
Full ROM rebuild/flash and live UI, init/SELinux IPC, boot reapplication, profile
picker and battery-consumption tests remain necessary before runtime claims.

Stingy validation (2026-10-04)
------------------------------
94 profile/rollback assertions passed using Android app_process on the connected
device with simulated control writes; the phone's battery settings were not
changed. Hidden app-op/network/Saver/dark-schedule API signatures resolved on
that device. The 16 native allowlist assertions passed on the host. Java/D8,
regenerated-smali assembly, and the full Settings classes4 DEX assembly passed.
All four Settings patches passed dry-run and real application with --fuzz=0
after the Settings source overlay on a fresh firmware decode in /tmp.
These are source/compatibility checks, not live Stingy enforcement, boot/restore
or battery-consumption measurements.

Large-journal recovery fix (2026-10-07)
--------------------------------------
The supplied failure journal contained 6,421 records and was 1,323,484 bytes,
which exceeded the former shared 1 MiB reader limit. All engine and boot paths
now use a dedicated 16 MiB journal reader. Journal writes enforce the same byte
limit before replacing the durable file. Profiles have their own 64 KiB limit.
Errors identify the file and limit instead of only saying "File too large".

Rollback entries omit UI labels, help, choice arrays, current/effective values,
and boot identifiers for non-kernel settings. Original values, kind, target,
UID, app-op, custom dark schedule metadata, kernel boot IDs, and unknown fields
are retained. Reading old journals compacts them in memory; their first rewrite
backs up an existing file above 1 MiB as original.json.legacy.bak.

New snapshots are saved together before new control writes. A capacity or disk
failure aborts new writes and preserves the previous journal. Restoration uses
checkpoints of up to 64 records; interruption leaves originals on disk for safe
retry. A failed checkpoint also restores those records to the in-memory journal.
This avoids thousands of complete journal rewrites for large app inventories.

Regression tests include a synthetic 6,500-entry legacy file and optionally the
actual failing journal, byte-identical legacy backups, exact rollback-field
preservation, capacity rejection without file replacement or control writes,
failed checkpoint retention, and simulated full restoration. Optional fixture:
    CLASSPATH=/data/local/tmp/unica-journal-tests.jar app_process /system/bin \
        io.mesalabs.unica.battery.BatteryTests /data/local/tmp/original-fixture.json
All control writes in these tests are simulated; they do not enable a preset.
The temporary compact recovery file is only for its originating phone. Do not
replace a journal with {} or clear Settings data: that discards saved originals.

Standby policy handling (2026-10-09)
-----------------------------------
Exempted (5) apps are skipped for bucket writes, without creating a new undo
record. Existing undo records remain intact. If a bucket write returns a known,
less restrictive bucket (for example Never 50 -> Working set 20), the result is
reported as an Android policy limit, not as an applied restriction. Unexpected
readbacks and real API exceptions remain failures.

When exact bucket restoration is not accepted, the original is retained and
reported as deferred. System-owned originals such as Exempted (5) are never
sent as invalid arguments to the standby setter. A later exact restoration
clears the record normally. This does not bypass Android exemptions or claim
that a refused restriction took effect.

The UI distinguishes completed operations with policy limits from actual
failures and reports the deferred-original count. Reflection exceptions are
unwrapped to show their underlying type/message; a missing message falls back
to the exception type instead of displaying null.

144 Android regression assertions passed, including bucket-policy outcomes,
error reporting, deferred/exact rollback and the supplied 6,421-entry journal.
All setting writes were simulated. Java/D8, generated-smali/full classes4 DEX
assembly, zero-fuzz Settings patch-stack checks and git diff --check passed.
Rebuild/deploy Settings for the new behavior; live enforcement remains subject
to the device's Android/Samsung policy.
