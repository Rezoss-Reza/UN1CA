#!/usr/bin/env bash
set -Eeuo pipefail
ROOT=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
BASE=/home/rezoss79/dev/sembilan/out/archived/wondertap-port-20261023/build-5.15.220
python3 "$ROOT/prepare-compat-headers.py"
export PATH="$BASE/clang-r584948/bin:$PATH"
make -j2 -C "$BASE/kernel" O=out ARCH=arm64 CC=clang LD=ld.lld LLVM=1 LLVM_IAS=1 NOSTDINC_FLAGS="-I$ROOT/vendor-compat/include -nostdinc -isystem $BASE/clang-r584948/lib/clang/22/include" M="$ROOT/provider" KCFLAGS="-I$ROOT/wonder-samsung-api/wlan/wonder/include" modules
make -j2 -C "$BASE/kernel" O=out ARCH=arm64 CC=clang LD=ld.lld LLVM=1 LLVM_IAS=1 NOSTDINC_FLAGS="-I$ROOT/vendor-compat/include -nostdinc -isystem $BASE/clang-r584948/lib/clang/22/include" M="$ROOT/wonder-samsung-api/wlan/wonder" EXTRA_CFLAGS="-I$ROOT/wonder-samsung-api/wlan/wonder/include -DWONDER_MAC80211_STA_LEGACY -DCONFIG_NL80211_TESTMODE=1" KBUILD_EXTRA_SYMBOLS="$ROOT/stock-vendor-exports.symvers" modules
"$BASE/clang-r584948/bin/llvm-nm" --undefined-only \
    "$ROOT/wonder-samsung-api/wlan/wonder/wonder.ko" > "$ROOT/wonder-import-symbols.txt"
for SYMBOL in ieee80211_rate_control_register ieee80211_rate_control_unregister rate_control_set_rates; do
    grep -q " $SYMBOL$" "$ROOT/wonder-import-symbols.txt"
done
python3 - "$ROOT/wonder-samsung-api/wlan/wonder/wonder.ko" <<'PY'
from pathlib import Path
import sys

if b"wonder_minstrel_ht\0" not in Path(sys.argv[1]).read_bytes():
    raise SystemExit("Wonder private software rate-control algorithm is missing")
PY
