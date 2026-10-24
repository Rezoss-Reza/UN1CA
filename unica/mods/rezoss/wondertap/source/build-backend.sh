#!/usr/bin/env bash
set -Eeuo pipefail
ROOT=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
BASE=/home/rezoss79/dev/sembilan/out/archived/wondertap-port-20261023/build-5.15.220
python3 "$ROOT/prepare-compat-headers.py"
export PATH="$BASE/clang-r584948/bin:$PATH"
export CCACHE_DISABLE=1
# Samsung's 5.15 header has the newer multi-link/puncturing notification API.
# Refuse this profile unless the actual declaration matches the required API.
grep -qF 'unsigned int link_id, u16 punct_bitmap' "$BASE/kernel/include/net/cfg80211.h"
grep -qF 'RATE_INFO_FLAGS_EHT_MCS' "$BASE/kernel/include/net/cfg80211.h"
# Kbuild consumes WLAN_CTRL_NAME, not CONFIG_WLAN_CTRL_NAME from the profile.
# Without this argument the /dev/wlan creation and state handlers are stubs.
# CHIP_NAME selects the firmware/INI directory; MODNAME is the module identity.
# Without CHIP_NAME, Kbuild defaults to qca_cld3_kiwi_v2 instead of kiwi_v2.
make -j12 -C "$BASE/kernel" O=out ARCH=arm64 CC=clang LD=ld.lld LLVM=1 LLVM_IAS=1 NOSTDINC_FLAGS="-I$ROOT/vendor-compat/include -nostdinc -isystem $BASE/clang-r584948/lib/clang/22/include" \
 M="$ROOT/wlan-driver/qcacld-3.0" WLAN_ROOT="$ROOT/wlan-driver/qcacld-3.0" \
 WLAN_COMMON_ROOT=cmn WLAN_COMMON_INC="$ROOT/wlan-driver/qca-wifi-host-cmn" \
 WLAN_FW_API="$ROOT/wlan-driver/fw-api" WLAN_PLATFORM_INC="$ROOT/wlan-driver/platform/inc" \
 KBUILD_EXTRA_SYMBOLS="$ROOT/stock-vendor-exports.symvers $ROOT/provider/Module.symvers" \
 MODNAME=qca_cld3_kiwi_v2 CHIP_NAME=kiwi_v2 WLAN_CTRL_NAME=wlan CONFIG_QCA_CLD_WLAN=m CONFIG_QCA_CLD_WLAN_PROFILE=anorak_gki_kiwi-v2 \
 'WLAN_CFG_OVERRIDE=CONFIG_DRIVER_PASSTHRU_MODE=y CONFIG_BUILD_TAG=n CONFIG_IPA_OFFLOAD=n CONFIG_CNSS_OUT_OF_TREE=y CONFIG_S23U_LEGACY_CNSS=y CONFIG_WLAN_SYNC_TSF_PLUS=n CONFIG_WLAN_SYNC_TSF_PLUS_NOIRQ=n' \
 KCFLAGS="-I$ROOT/wonder-samsung-api/wlan/wonder/include -DCFG80211_SINGLE_NETDEV_MULTI_LINK_SUPPORT -DCFG80211_11BE_BASIC -DCONFIG_NL80211_TESTMODE=1 -DCFG80211_SAE_AUTH_TA_ADDR_SUPPORT" \
 "${@:-modules}"

if [[ "$#" -eq 0 || " $* " == *" modules "* ]]; then
    "$BASE/clang-r584948/bin/llvm-nm" --defined-only \
        "$ROOT/wlan-driver/qcacld-3.0/qca_cld3_kiwi_v2.ko" > "$ROOT/native-wifi-control-symbols.txt"
    grep -Eq ' wlan_hdd_state_fops([.]|$)' "$ROOT/native-wifi-control-symbols.txt"
    grep -q ' wlan_hdd_state_ctrl_param_write' "$ROOT/native-wifi-control-symbols.txt"
    python3 - "$ROOT/wlan-driver/qcacld-3.0/qca_cld3_kiwi_v2.ko" <<'PY'
import mmap
import sys

with open(sys.argv[1], "rb") as stream, mmap.mmap(stream.fileno(), 0, access=mmap.ACCESS_READ) as data:
    expected = b"wlan/qca_cld/kiwi_v2/WCNSS_qcom_cfg.ini\0"
    obsolete = b"wlan/qca_cld/qca_cld3_kiwi_v2/WCNSS_qcom_cfg.ini\0"
    if data.find(expected) < 0 or data.find(obsolete) >= 0:
        raise SystemExit("Wrong S23U WLAN INI directory in linked module")
print("Validated native WLAN config path: wlan/qca_cld/kiwi_v2/WCNSS_qcom_cfg.ini")
PY
fi
