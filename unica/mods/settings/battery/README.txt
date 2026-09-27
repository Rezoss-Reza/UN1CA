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
40 Java profile/rollback assertions and 16 native allowlist assertions passed on
Android. The earlier direct ceiling check accepted 2841600 kHz on policy7 and
immediately restored 3360000 kHz; no permanent profile was enabled.
SELinux compilation passed in Android init's runtime mode for both untouched
firmware and the current customized ROM policy. Strict neverallow comparison
showed the same 226 pre-existing mixed-version vendor conflicts as the untouched
baseline, with zero new worker conflicts. The installer is idempotent.
Full ROM rebuild/flash and live UI, init/SELinux IPC, boot reapplication, profile
picker and battery-consumption tests remain necessary before runtime claims.
