Maximum power saving: optional essential apps
============================================

The existing Essential apps section and Samsung SwitchCompat are retained.
Smart Manager, Settings, Clock and the currently selected default launcher have
no switches. Other eligible essential apps initially remain ON. OFF selections
persist across mode changes and reboot; only Maximum mode applies suspension.
Turning a switch ON, leaving Maximum mode, or starting EBS while Maximum is
inactive removes Smart Manager's suspension. Other administrators' suspensions
are not removed. No app is disabled or uninstalled.

Optional package identities (only apps originally classified as essential):
  com.android.vending
  com.google.android.apps.walletnfcrel / com.samsung.android.spay
  com.samsung.android.messaging / com.google.android.apps.messaging
  com.samsung.android.dialer / com.google.android.dialer

The screenshot's Play Store, Wallet, Samsung Messages and Phone are covered.
Nonessential alternative dialers/message apps stay in All apps. Work-profile
controls retain Samsung's stock policy; these new switches apply to the app's
own Android user. Google Play services is not made optional.

Selections, discovered essential identities and pending restoration records use
separate per-user Settings.Secure keys. Writes are read back. Before suspension,
a recovery marker is written; it is cleared only after PMS accepts restoration.
Rejected changes restore the previous selection and display an error. Recovery
records survive interrupted changes and failed restoration for later retry.

PMS integration
---------------
SmartManagerCN 17.0.03.30 uses android.uid.system (1000). UID alone is therefore
not the gate. setPackagesSuspended selects a copy of canSuspendPackageForUser
only when the suspending UserPackage names com.samsung.android.sm_cn. The
original method remains unchanged for other callers and unsuspendable queries.
The copied check permits explicit, previously discovered optional essentials
only for a matching platform-signed Smart Manager UID/user, low_power=1 and
sem_power_mode_ebs_option=1. It selectively relaxes verifier/default-dialer/
required-system-package exclusions. Launcher, device-admin, protected-package,
installer, permission-controller, shared-library and app-op exclusions remain.
Android may still reject an independently protected target; the UI reports that
failure. The framework permission and caller-package checks remain intact.

Stock mode exit excludes default-role apps from its normal unsuspension list.
EssentialApps.onMode restores our journal separately before that stock path.
EbsInitService retries the journal on startup if Maximum mode is inactive.

Source and build integration
----------------------------
The two patches are under rezoss/smali and are picked up automatically by
scripts/internal/apply_modules.sh. Both patches must ship together. No change
to customize.sh or the original prebuilt APK is needed.

Exact inputs used for generation:
  SmartManagerCN.apk SHA-256:
    06f65373071f8bf7a42a03121fd1804938fdef1212cc5faf328f8f85c81d7483
  out/fw/SM-S918B_EUX/system/system/framework/services.jar SHA-256:
    131662a5b157f0f35563156793df248461a26cc053885073a81d163db69ac4ca

To regenerate, first source buildenv.sh dm3q and decode these inputs into new
/tmp folders using project Apktool, --no-debug-info and the current Samsung
framework tag/cache. Then run:
  unica/mods/rezoss/maximum-essentials/build.sh CLEAN_APK_DECODE CLEAN_SERVICES_DECODE
Existing patch files are backed up before replacement. Do not stage the backups.
The generator reads the decoded trees and writes unified patches only. Stubs in
build.sh are compile-time declarations and are never included in either patch.

Host checks:
  python3 unica/mods/rezoss/maximum-essentials/tests/test_helpers.py
  bash -n unica/mods/rezoss/maximum-essentials/build.sh

Validation must additionally dry-run and apply both patches with --fuzz=0 on
clean temporary decodes, check compatibility with the preceding patch stack,
rebuild the temporary APK/JAR, and run unzip -tq on both outputs. Changes to the
original methods preserve .locals, labels, catches and control flow except for
the explicit inserted calls/dispatch. No original method is replaced.

Device validation after a rebuilt ROM is installed is still required:
  - Inspect the native UI in light/dark mode, scrolling and accessibility.
  - Confirm four fixed apps have no switches, and optional switches start ON.
  - Toggle each optional app outside/inside Maximum and check actual suspension.
  - Leave Maximum, then reboot both inside and outside Maximum; verify restoration.
  - Verify failed writes/suspension do not leave a misleading switch state.
  - Verify calls/messages behavior deliberately sacrificed by their OFF switches.
Host tests simulate Android Settings, Binder and views. They do not establish
ART verification, Samsung service behavior, UI rendering or battery savings.
