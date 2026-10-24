Relax SDHMS (dm3q only)

Build the generated smali patches with:
    bash unica/mods/settings/thermal/build.sh

The APK SHA-256 guard pins the inspected S918BXXUAGZIF SDHMS input. Review the
field/method ABI, policy assets and persistent restart path before updating it.
Stubs are compile-time declarations only and are excluded from generated DEX.

Settings adds a nonpersistent switch directly below Force peak refresh rate.
ON requires the red heat-risk confirmation. OFF removes the marker without a
confirmation. The marker itself persists across phone reboots until switched off.

The receiver is injected into SDHMS and runs under its existing UID, process and
SELinux context. Its manifest requires WRITE_SECURE_SETTINGS, and its code also
requires a shared broadcast identity of UID 1000 / com.android.settings. There
are no root-shell commands, new SELinux permissions, arbitrary paths, service
stops, kernel edits or phone reboot requests.

The only marker is the SDHMS app's own ssrm.disable. It must be a regular,
empty, singly linked system:system file; symlinks/foreign files are rejected.
The receiver creates it as 0600 or deletes it, fsyncs, then ends only its own
process after sending the reply. The existing persistent application startup
reloads the policy. Settings polls for a new PID, parsed policy mode and marker
agreement before reporting success. Policy-loader fallback errors are not
reported as successful normal-mode restoration. Reopen the page after an error
to inspect the saved marker and try turning it off.

The policy completion hook adds one static invoke to settings.k.n without
changing registers, labels or original control flow. The existing DVFS patch
changes policy selection elsewhere, not this completion hook. All custom smali
is generated as patches; extracted firmware and out/target are not source edits.

This option selects Samsung's permissive ssrm_default policy. It does not
implement a new safe-temperature ceiling and does not preserve the normal
model-specific emergency policy. The warning describes burns, battery aging,
hardware damage and the limits of external cooling/kernel protection.

Offline compilation/patch checks are not on-device restart or thermal proof.
Validate both directions after deploying through the normal ROM build path:
cancel warning -> no marker change; ON -> new SDHMS PID + relaxed state;
OFF -> marker absent + another PID + normal state; no phone reboot; repeat after
reopening Settings and after boot. Check enforcing SELinux logs and SDHMS
initialization. Do not heat the phone to emergency trip points for validation.
