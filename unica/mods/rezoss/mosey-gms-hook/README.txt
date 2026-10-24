Mosey GMS Hook 1.5: exact-app provider registry experiment

Build:
  bash unica/mods/rezoss/mosey-gms-hook/build.sh
Output:
  out/archived/mosey-gms-hook/MoseyGmsHook-1.5.apk
Optional output APK argument; existing files are never overwritten.
SDK overrides: ANDROID_HOME, ANDROID_JAR, D8, AAPT2, APKSIGNER.
Requires Java, Android SDK 37 platform/build tools, Xposed compile-only JAR.
No device access or full ROM build. Not automatically integrated by customize.sh.

Unlike the v1.4.1 packaging-only correction, v1.5 compiles maintained Java.
input/MoseyGmsHook-1.4.apk is preserved historical input only, not runtime code.
compile-only/xposed-api-82.jar is never bundled into the module DEX.
The same repository AOSP test key signs this upgrade as the installed module.

Runtime profiles (other version codes are skipped with a log):
  Google Play services   26.41.31 / 264131035
  Mosey                 1.0.980159239 / 60715
  Quick Share           13.9.02.31 / 1390231205
Pinned offline APK hashes and raw DEX targets: targets.json.
A same-version APK with different bytes is not independently runtime hash-checked;
revalidate against targets.json if replacing these artifacts.

Behavior change:
  GMS kbfs.V()Z, implementation of kbfr.V(), returns true after its normal
  successful execution. It reads phenotype flag 45704091 (default false).
  NearbySharingChimeraService.onCreate uses it to start the external provider
  registry; ac(Z)V uses it in registry lifecycle eligibility.
  All other GMS feature flags, including T() and delayed-start policy, remain
  original. Package whitelist, signature, permissions and supported provider
  metadata checks are preserved. No Tidepool/hotspot or identity gates forced.

Passive observers:
  - GMS registry startup/lifecycle, Mosey validation/binding, external provider
    discovery and advertising.
  - Mosey feature flag, discovery/advertising, native service lookup/start.
  - Quick Share original Mosey support and provider-enable request.
  Logs contain result/status/exception type, not peer IDs, filenames or payloads.
  Each observer logs its first eight entries/exits. Install messages are separate.

Removed obsolete behavior:
  - Old jyzi/jyzn/dvxw etc GMS hook guesses.
  - Old Quick Share support override (captured app already supports ProtocolX).
  - Old Mosey channel-1 override. The current channel selector is a coroutine;
    preserve its country/allowed-channel policy and actual radio capabilities.

Validate all actual target definitions against a fresh snapshot:
  python3 unica/mods/rezoss/mosey-gms-hook/tools/verify_targets.py SNAPSHOT_DIR
Snapshot format: artifacts.json records package, local_path, sha256 for each APK,
including the exact three base artifacts in targets.json. Original APKs stay
outside source under out/archived. Validation rejects inherited/reference-only,
abstract/native/code-less and ambiguous matches; it does not prove execution.

Test when ready:
1. Install v1.5 over v1.4.1. Retain the existing three-app LSPosed scope:
   com.google.android.gms
   com.samsung.android.app.sharelive
   com.google.android.mosey
2. Restart affected processes or reboot when convenient; this build does neither.
3. Open Quick Share Apple sharing / try a controlled receive or send.
4. Read logcat tag MoseyGmsHook. Expected checkpoints:
   installed registry gate kbfs.V()Z flag=45704091
   registry gate kbfs.V original=... effective=true
   sharing service startup ... registryStarted=true
   Mosey provider validation result=true
   Mosey provider bind result=present
   Mosey discovery/advertising entered
   native Mosey service lookup result=present
   native Mosey start entered/completed
Any missing/failed checkpoint identifies the next layer; installation alone is
not proof of binding, firmware support, handshake, or Apple file transfer.
5. Disable the module in LSPosed and restart affected processes to restore
   original GMS gate behavior. No kernel/module/firmware change required.

Recommended-scope mechanism:
https://github.com/LSPosed/LSPosed/wiki/Module-Scope
An APK upgrade does not forcibly replace an existing LSPosed scope.
Xposed method hook API:
https://api.xposed.info/reference/de/robv/android/xposed/XposedBridge.html

Evidence snapshot:
out/archived/mosey-hook-repair-20261024-145620/
Contains original APK identities, decoded selections, raw target validation,
REA Evidence records, and reports. APK copies and logs are private local inputs.
Offline build/signature/target checks passed. v1.5 has not run on the phone;
actual provider activation, runtime classloader visibility and Apple sharing
remain unverified until the next controlled device test.
