Scheduled power on for dm3q / SM-S918B
=====================================

ROM-native standalone launcher app, platform signed and using the system UID.
No root-manager dependency, framework smali patches, boot scripts, automatic
shutdown, recurring schedules, or background service. Nothing is armed until
the user saves a date/time. Samsung's driver queues the alarm for shutdown.

Location: unica/mods/rezoss/scheduled-power-on
Parent: unica/mods/rezoss/customize.sh sources integrate.sh.
Only dm3q is packaged. A disable marker in this directory skips integration.
No prebuilt APK is stored in source; build.sh compiles Java/JNI and signs with
the build's aosp/unica platform certificate. Android SDK 37 and NDK
28.2.13676358 are required (same defaults as the other local Rezoss builders).
Firmware gate: inspected sec_pon_alarm.ko SHA-256
9949d2045d979e8f25d0f84d7bffe2364263195e227155d2364e570bf29a31d6.
Revalidate modified/replacement kernels and modules before relying on this app.

Usage after rebuilding and flashing
----------------------------------
Open "Scheduled power on" from the launcher. Choose a date/time between two
minutes and seven days ahead, then Save. Local time is converted to UTC by the
app; Samsung's driver converts UTC into the hardware RTC's offset time base.
After the driver accepts the command, shut the phone down manually.
The app does not initiate shutdown. Choose a new date/time for another startup.
Cancel while Android is still running; an already powered-off phone cannot
receive a new schedule. Keep battery charge available. Startup does not bypass
the normal lock-screen PIN or first-unlock requirements.

Experimental limitation: static driver inspection and command acceptance
do not prove the bootloader starts normal Android from full power-off. Test a
short one-shot schedule without USB/charger first, then test with a charger
separately. Do not use this as your only essential alarm until those tests pass.
If the phone reboots before you shut down, reopen the app and save the time
again. The app records the boot count and asks you to resave after a reboot.
No alarm programming, shutdown, reboot, or app installation is performed by
the host build/tests. Changes to system time after saving require rescheduling.
Timezone changes only alter the displayed local equivalent of the same instant.

Driver contract and evidence
----------------------------
Exact firmware: out/fw/SM-S918B_EUX/vendor_dlkm/lib/modules/sec_pon_alarm.ko
REA/Ghidra pon_alarm_ioctl at 0x1013e4:
ev_e37c31979e83480f8d05dc29467c9d4f1fc8eb853b678ba99baed76d8708f0aa
Command 0x400e6107 (_IOW('a',7,char[14])) accepts ASCII:
  1YYYYMMDDHHMM\0 : enable, UTC calendar date, seconds zero
  0202002010101\0 : disable (date digits are ignored in the disable branch)
The driver handles the wall-clock/RTC offset, rounds RTC epoch down to a
multiple of four seconds for mode 1, calls pmic_rtc_setalarm, and persists
the Samsung SAPA record through sec_param_set. Do not convert to RTC time in
userspace as well. Persistence writes are not independently verified here.

The ioctl reports success even for some underlying failures. The app requires
RTC_RD_TIME (0x80247009) support before writing and validates the representable
RTC time, but reports only DRIVER ACCEPTANCE, not hardware or persistence proof.
The driver handles persistence itself; this app never reads raw param partitions.
Samsung's cancellation branch writes reserved RTC date 2020-02-01 01:01:04,
not necessarily an alarm with enabled=0. It queues that reserved date for
shutdown. Cancellation also reports driver acceptance, not verified hardware.
The app does not issue raw RTC_WKALM_SET or modify the RTC clock.
An uncertain-state flag is saved before programming. A failed ioctl
keeps that flag visible; failed local-state persistence after programming never reports
success. This app does not automatically clear alarms it did not program.
Existing Samsung power-on alarms share this hardware slot; saving replaces
that slot, and cancellation clears Samsung's power-on alarm record.

REA/Ghidra pon_alarm_read:
ev_139ec5e1dd60b7969f81d1f8efbaaea228cbb76c0e3dbc53fc16dfcf780f2c1d
This read returns the driver's triggered flag, NOT the scheduled date. It is
not suitable for verifying that a write programmed the desired date.

The exact vendor_boot.img was unpacked in /tmp to inspect rtc-pm8xxx.ko:
SHA-256 0225a0a8b721bbba9ff56036cd1f726f50bdf10cfcf207b62d152d6828a54455.
pmic_rtc_setalarm copies the requested date into pwron_alarm in RAM:
ev_f58f572b3767dc74a72b61d074ba36cff9058e351a2c28cdbe26c4024cc7b6df
pm8xxx_rtc_shutdown applies pwron_alarm through pm8xxx_rtc_set_alarm:
ev_facfcebac9089545a155a5f357c181c27e70cb6245ac58c914b8e1a623dcb6b7
pm8xxx_rtc_read_alarm reads live PMIC registers, not pwron_alarm:
ev_687bfaa163e61c1ba045069d5a27c69a9e0544a6e77b72ff18bb80faab625d11
Therefore RTC_WKALM_RD cannot verify the pending scheduled power-on date before
shutdown. Do not add a comparison against that unrelated live alarm.

Security/build changes
----------------------
No exported service, broadcast receiver, permission grant, shell command,
network access, or reboot capability. The launcher activity ignores intent
extras; only UI Save/Cancel calls program the alarm. JNI opens fixed paths.
The system UID is needed because stock init sets /dev/power_on_alarm to 0220
system:radio after boot. No chmod/ueventd changes are made.
Vendor policy grants system_app access to alarm_device for only ioctl 0x6107,
and RTC read access for only ioctl 0x7009. These grants apply to the
system_app domain (including other platform-signed system-UID apps), not only
this package. Existing platform policy still applies. No raw RTC writes added.
The installer changes the policy identifier to invalidate stale precompiled
policy. A policy error could prevent boot; compile/validate the final assembled
ROM policy as well as the source-firmware policy before flashing.

Verification from repository root
---------------------------------
bash unica/mods/rezoss/scheduled-power-on/tests/run.sh
source buildenv.sh dm3q
bash unica/mods/rezoss/scheduled-power-on/build.sh /tmp/RezossPowerOn-new.apk
bash unica/mods/rezoss/scheduled-power-on/tests/integration_test.sh
bash -n unica/mods/rezoss/customize.sh
git diff --check

The output path must not already exist. Offline tests exercise UTC formatting,
RTC offset/rounding, bounds, cancellation encoding, and idempotent
policy installation. A full ROM rebuild/flash and controlled shutdown/startup
tests remain necessary to establish end-to-end operation.

For policy compilation, use tests/compile_policy.py FIRMWARE_ROOT SECILC_PATH.
This uses Android init's runtime -N flags. --strict enables neverallow checks;
--baseline compiles without this mod for comparison. Ubuntu secilc 3.5 is too
old for the firmware's nlmsg permissionx. The repository's vendored libsepol
and secilc can be copied and built in /tmp instead. The shipped firmware CIL
already contains cross-version neverallow conflicts in strict mode.
The bundled JNI library is also installed into RezossPowerOn/lib/arm64 for
system-app loading, with system_lib_file metadata. Samsung's preload allowlist
entry is supplied by a separate sysconfig XML without modifying the shared list.
