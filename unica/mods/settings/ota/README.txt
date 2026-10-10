ROM-native OTA updater (dm3q)

Entry: UN1CA Settings > OTA Update, in a separate group below Spoof/Extra.
Source: Google Drive folder 1AwB5M1gD24vCJm97u0CtZSdWzF0Vus7B.
Download latest OTA opens the public folder with ACTION_VIEW. The user downloads
its single latest ZIP using a browser/Drive app, returns to OTA Update, then
uses Select a local OTA ZIP. No API key, account credentials or folder scraping.
The app verifies the signed metadata inside the selected ZIP before installation.
If no browser/link handler is installed, it displays a message and still supports
local selection. Folder sharing must allow downloads for anyone with the link.

Build/publication:
- Compile updater changes with: unica/mods/settings/ota/build.sh
- Run: source buildenv.sh dm3q; unica make_rom -ota
- Upload only the latest OTA .zip to the public folder. The .zip.ota.json catalog
  remains an optional build artifact and is not required for this browser/local
  selection flow. No upload is performed by these scripts.
- Signed metadata binds the required base and target ro.unica.timestamp, device,
  package size/hash and entry hashes. ZIP filenames do not establish identity.
- Existing OTA ZIPs without signed metadata cannot use the new in-ROM updater.
  Install one full release containing the updater first, then publish an OTA
  whose base is that exact release. Local selection also requires signed metadata.

Publisher key:
- Private key: ~/.config/unica/ota-signing.pem, generated once with mode 600.
  Override only with UNICA_OTA_SIGNING_KEY pointing to the SAME publisher key.
- Public key: ota/trust.der and generated OtaTrust.java, included in the ROM.
- Back up the private key outside the checkout; losing it prevents publishing
  updates trusted by installed ROMs. An existing public key with a missing or
  mismatching private key causes generation to stop. No private key is tracked.

Installation flow:
- Settings verifies publisher signature, base identity and every signed ZIP entry.
- Fixed-path ROM worker copies the verified ZIP into root-owned protected staging,
  checks free space, refuses an existing recovery request and atomically creates
  /cache/recovery/openrecoveryscript. No caller-selected paths or shell commands.
- ORS contains only install /data/misc/unica_ota/staged/update.zip; it contains no
  unconditional reboot command. A /cache/unica-ota/auto marker requests reboot
  only after both the original updater and DFE return success.
- Recovery checks installed system identity again; target identity is also
  accepted there for retries after a partially applied system partition. Recovery
  block verification still decides whether a retry can safely apply each delta.
- Initial 5-second native input gate watches EV_KEY/KEY_VOLUMEDOWN, including a
  held key. Cancel or input-monitor failure aborts before partition writes.
- Recovery saves result and log to /data/misc/unica_ota/staged/ . Failure stays in
  recovery. Successful automated installation requests reboot into Android.

Validation:
Java/D8 compilation, DEX assembly, native ARM64 compilation, zero-fuzz additive
Settings patch application, metadata signatures/tampering, compatibility rules,
temporary complete Settings APK rebuild and ZIP integrity, combined SELinux
compilation using Android init runtime flags (-N), end-to-end signed synthetic
OTA/catalog generation, and simulated recovery cancellation/failure/reboot tests.
See tests/run.sh. Runtime-mode policy compilation does not prove device access.
Actual UI, browser download/return flow, SELinux runtime, encrypted
/data access, OrangeFox ORS execution, hardware key gate and automatic reboot
require a device/recovery test. Do not distribute the automatic flow as proven
until those tests pass. No automatic installation has been performed here.
