Power schedule for dm3q / SM-S918B
==================================

UN1CA Settings > Power schedule, immediately below Battery Saving and above
Advanced GPU Tuning (Konabess). The scheduler remains a small platform-signed,
system-UID ROM package under mods/rezoss; it no longer has a launcher icon.
Its activity requires REBOOT permission to open. Its alarm/boot receiver is
not exported. The Settings menu is hidden if this mod or its Rezoss parent is
disabled, or the build target is not dm3q.

User controls
-------------
Select Power on, then choose:
  Save and Shutdown Now
  Save and I'll shutdown manually
The first option asks for final confirmation, saves the hardware power-on
request durably, and invokes Android's normal shutdown API. Any failure before
that point prevents the shutdown request. No root-manager or shell is used.

Enable Separate power-off and power-on times to select two dates/times and
Save schedule. Power off must be at least 2 minutes ahead; power on must be
at least 3 minutes after power off and no more than 7 days from saving.
All schedules are ONE-TIME. The confirmation shows both selected times.
Cancel automatic power off keeps the power-on alarm. Cancel entire schedule
also sends Samsung's cancellation command. Opening the page or changing a
picker never arms an alarm until the user chooses a save action.

The screen uses rounded cards, larger row labels, day/night colors and the
same UN1CA Settings wallpaper/transparency keys as Battery Saving. The design
reference is out/archived/example_UI.jpg. It uses the user's wallpaper rather
than copying the wallpaper shown in that reference. Editing a draft or changing
the separate-times switch takes effect only after saving.

Runtime behavior
----------------
Android AlarmManager delivers a one-shot exact ELAPSED_REALTIME_WAKEUP event
for the selected power-off time. There is no permanent background service or
polling loop. A short receiver worker holds a bounded wake lock while handling
an event. A generation token rejects obsolete deliveries; a consumed event is
persisted before requesting shutdown. A fresh Samsung power-on command must
succeed before any scheduled shutdown is requested.

An event arriving early, over 60 seconds late, with an uncertain save, with a
changed RTC offset, or with under 2 minutes remaining until power on is skipped.
A reboot or date/time change rearms future events, but never catches up a missed
shutdown. A power-off time less than 2 minutes away at restoration is skipped.
The times represent absolute instants; a timezone change changes their displayed
local equivalent. Device-protected private state allows restoration before
first unlock. The old app's credential-protected schedule is migrated when the
new Settings screen is first opened. First-unlock PIN requirements are unchanged.

Driver contract
---------------
Native code and the validated Samsung command format are unchanged:
sec_pon_alarm.ko SHA-256:
9949d2045d979e8f25d0f84d7bffe2364263195e227155d2364e570bf29a31d6
Command 0x400e6107 (_IOW('a',7,char[14])) accepts ASCII:
  1YYYYMMDDHHMM followed by NUL: enable using a UTC calendar date
  0202002010101 followed by NUL: cancel
The driver converts wall-clock time into RTC time and reserves its low two
bits for alarm mode. Do not perform the conversion twice. Cancellation queues
Samsung's reserved RTC date 2020-02-01 01:01:04.

pmic_rtc_setalarm stores pwron_alarm in RAM; pm8xxx_rtc_shutdown applies that
saved alarm during shutdown. RTC_WKALM_RD reads a different live alarm and
cannot verify the pending power-on request. Some driver/persistence failures
are suppressed by the ioctl; command acceptance is not independent readback
proof. The original scheduled startup was reported working by the user. The
new automatic shutdown and boot/clock restoration need device testing.

REA evidence from the exact inspected firmware:
pon_alarm_ioctl:
ev_e37c31979e83480f8d05dc29467c9d4f1fc8eb853b678ba99baed76d8708f0aa
pmic_rtc_setalarm:
ev_f58f572b3767dc74a72b61d074ba36cff9058e351a2c28cdbe26c4024cc7b6df
pm8xxx_rtc_shutdown:
ev_facfcebac9089545a155a5f357c181c27e70cb6245ac58c914b8e1a623dcb6b7
pm8xxx_rtc_read_alarm:
ev_687bfaa163e61c1ba045069d5a27c69a9e0544a6e77b72ff18bb80faab625d11
rtc-pm8xxx.ko from the exact vendor_boot.img SHA-256:
0225a0a8b721bbba9ff56036cd1f726f50bdf10cfcf207b62d152d6828a54455

Build and verification
----------------------
The existing Rezoss customize.sh sources integrate.sh. The APK is compiled
from Java/JNI and signed with the build's aosp/unica platform certificate.
Build defaults: Android SDK 37, build tools 37.0.0, NDK 28.2.13676358.
Native library loading is supported by the installed lib/arm64 directory.
Existing SELinux rules are unchanged: Samsung ioctl 0x6107 plus read-only RTC
ioctl 0x7009 for system_app. This applies to other system_app domain processes
as well. No raw RTC writes, sys.powerctl access, framework smali changes, or
shell/root permissions are added. REBOOT, USE_EXACT_ALARM, WAKE_LOCK and
RECEIVE_BOOT_COMPLETED are declared by the package for its new functions.

From the repository root:
  bash unica/mods/rezoss/scheduled-power-on/tests/run.sh
  source buildenv.sh dm3q
  bash unica/mods/rezoss/scheduled-power-on/build.sh /tmp/PowerSchedule-new.apk
  bash unica/mods/rezoss/scheduled-power-on/tests/integration_test.sh
  bash -n unica/mods/settings/customize.sh
  git diff --check

Output APK paths must not already exist. Host schedule tests inject fake
hardware, timers, storage and shutdown operations. They cover save ordering,
permission/storage/driver failures, delayed and stale deliveries, replay,
cancellation, clock changes and missed events. UI integration tests verify
menu order, intent/resources, component protection and disabled-mod behavior.
No test installs anything on the phone, arms a real alarm or requests shutdown.

Rebuild/flash both Settings and the scheduler package to expose the new menu.
This change is not a live-device installation. Keep sufficient battery charge
for power on; shut down only after saving the intended schedule.
