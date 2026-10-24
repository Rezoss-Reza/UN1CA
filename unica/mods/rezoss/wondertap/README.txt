S23U experimental Wondertap ROM integration

Enabled by default for dm3q. Requires the exact validated kernel Image from
common-5.15.220-3d64R-plain-031026-122105.zip and the stock vendor dependencies
recorded in manifest.json. Validation examines the final repacked boot.img.
The existing kernel auto-updater can select a newer kernel: this experiment
then stops the build until that kernel's ABI is revalidated or testing is disabled.
A different kernel release does not automatically require rebuilding modules.

Installs into vendor_dlkm/lib/modules:
  qca_cld3_kiwi_v2.ko, s23u_wonder_provider.ko, wonder.ko
Updates modules.dep/load/alias/softdep. The dependency graph loads the provider
before Qualcomm Wi-Fi, and Qualcomm Wi-Fi plus mac80211 before Wonder.
Existing Mosey Android services, permissions and libraries remain required.

2026-10-24 native Wi-Fi startup repair:
The original candidate loaded on the phone but did not create /dev/wlan.
Samsung's matching libwifi-hal.so writes "ON" to that character device when
starting Wi-Fi. Kbuild requires the make argument WLAN_CTRL_NAME=wlan; the
CONFIG_WLAN_CTRL_NAME entry in the profile does not enable this code by itself.
The corrected build passes that argument and checks for the real state-control
callbacks in the linked module. A subsequent device test confirmed /dev/wlan
is created. No HAL errors are bypassed and no dummy device node is supplied.

2026-10-24 native Wi-Fi config-directory repair:
The early-boot capture showed firmware initialization and calibration succeed,
followed by a missing wlan/qca_cld/qca_cld3_kiwi_v2/WCNSS_qcom_cfg.ini request
and a failed host-driver probe. The installed config is under kiwi_v2 instead.
The build now sets CHIP_NAME=kiwi_v2 while retaining MODNAME=qca_cld3_kiwi_v2
and WLAN_CTRL_NAME=wlan. A linked-module check requires the correct config path
and rejects the old path. The corrected config-path candidate needs a device
test.

2026-10-24 Wonder software rate-control repair:
Wonder now bundles the real Linux 5.15 Minstrel HT algorithm and explicitly
selects its private name, wonder_minstrel_ht. It registers the controller before
the Wonder platform driver and unregisters it after platform-driver cleanup.
Samsung's mac80211 and its global default parameter are unchanged. No fourth
kernel module or init-property workaround is required. Patch 0012 preserves
the existing provider and legacy station-layout adaptations. Shared wireless
and rate-control layouts and imported CRCs were checked against stock modules.
The corrected frontend has not been deployed; PHY registration and radio
operation must still be checked on the device.

This replaces the Wi-Fi driver. Earlier candidates booted and reached firmware
ready, but functioning Wi-Fi and Apple transfers have NOT been proven on a phone.
The latest candidate has only been validated offline until explicitly deployed.
Failure may prevent Wi-Fi or boot. No modules are loaded or flashed by this script.
Keep a known working ROM/kernel for recovery before flashing the test ROM.

Disable before a FRESH ROM rebuild:
  touch unica/mods/rezoss/wondertap/disable
or export REZOSS_ENABLE_WONDERTAP_TEST=false before running the build.
Disabling does not restore an already modified working output or flashed device.
Original build-time Wi-Fi and loader metadata are backed up on the host under
out/archived/wondertap-rom-stock/stock-*/ before first replacement.
Untouched out/fw firmware remains the source for a clean stock rebuild.
The manifest can recognize the exact earlier experimental QCA payload during
an incremental rebuild and replace it; arbitrary vendor drivers are rejected.
Pre-repair payloads are preserved under
out/archived/wondertap-native-wifi-fix-20261024/before/.
The candidate preceding the config-path repair is also preserved under
out/archived/wondertap-config-path-fix-20261024/before/.

Only the three modules and four loader metadata files enter the ROM. The source
directory is host-side provenance, not ROM payload. See source/SOURCE.txt.

Host verification performed:
  Real stock vendor tree copied to /tmp; real Samsung boot image repacked with
  the supplied kernel ZIP using the cached magiskboot; integration run using
  buildenv.sh dm3q and the actual ADD_TO_WORK_DIR helper.
  Payload hashes, SELinux labels, fs_config entries, loader dependencies and
  repeat-run metadata equality passed. Changed kernel/dependency, dependency
  cycle, blocklist and stale binary-index rejection passed. Raw/gzip kernel
  parsing, disable marker, environment toggle and target guard passed.
  These host packaging checks do not run a complete ROM build or a device test.

Syntax verification from the repository root:
  bash -n unica/mods/rezoss/customize.sh unica/mods/rezoss/wondertap/integrate.sh
  git diff --check -- unica/mods/rezoss/customize.sh

Revalidate and stage a prepared working tree without modifying its files:
  python3 unica/mods/rezoss/wondertap/prepare.py \
    --base unica/mods/rezoss/wondertap --work out/target/dm3q/work_dir \
    --stage "$(mktemp -d /tmp/wondertap-verify.XXXXXX)" \
    --backup out/archived/wondertap-rom-stock
This requires the kernel-selection step and stock vendor extraction to have
completed. It may create a host-side stock backup; it does not install the stage.

2026-10-24 PANIC REPAIR CANDIDATES
The previous payload reached native Wondertap but firmware asserted three times;
recovery then panicked at pmo_register_action_frame_patterns (NULL PMO context).
Patch0013 and current binaries guard that dereference, propagate suspend errors,
and stop retrying fatal vendor-init errors. Provider unchanged. Both modules
compile; import CRCs/vermagic match, but these candidates are not device-tested.
Firmware assertion remains unresolved: do not assume Apple sharing is safe or
working. Keep experimental Apple sharing disabled pending firmware investigation.
Rollback binaries/source are retained in /home/rezoss79/dev/sembilan/out/archived/wondertap-panic-fix-20261024-171653/before.
