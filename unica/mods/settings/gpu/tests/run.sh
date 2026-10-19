#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later
set -euo pipefail
: "${JSON_JAR:?Set JSON_JAR to a host org.json implementation; see ../README.txt}"
[[ -r "$JSON_JAR" ]] || { echo "Cannot read JSON_JAR: $JSON_JAR" >&2; exit 1; }
HERE=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
ROOT=$(cd "$HERE/../../../../.." && pwd)
AVB="$ROOT/external/android-tools/vendor/avb/libavb"
WORK=$(mktemp -d /tmp/unica-gpu-tests.XXXXXX)
INPUT="${1:-$ROOT/out/fw/SM-S918B_EUX/kernel/vendor_boot.img}"
cc -O2 -DAVB_COMPILATION -I"$AVB" -I"$AVB/crypto" -c "$AVB/crypto/sha256_impl.c" -o "$WORK/sha.o"
c++ -std=c++17 -O2 -DAVB_COMPILATION -I"$AVB" -I"$AVB/crypto" -Wall -Wextra -Werror "$HERE/image_test.cpp" "$WORK/sha.o" -o "$WORK/image-test"
"$WORK/image-test" "$INPUT" "$WORK/vendor_boot.img"
python3 "$ROOT/external/android-tools/vendor/avb/avbtool.py" verify_image --image "$WORK/vendor_boot.img"
python3 "$ROOT/external/android-tools/vendor/avb/avbtool.py" info_image --image "$WORK/vendor_boot.img" > "$WORK/avb.txt"
c++ -std=c++17 -O2 -DAVB_COMPILATION -I"$AVB" -I"$AVB/crypto" -Wall -Wextra -Werror -Wno-unused-function \
    "$HERE/transaction_test.cpp" "$WORK/sha.o" -o "$WORK/transaction-test"
c++ -std=c++17 -O2 -DAVB_COMPILATION -I"$AVB" -I"$AVB/crypto" -Wall -Wextra -Werror -Wno-unused-function \
    "$HERE/storage_paths_test.cpp" "$WORK/sha.o" -o "$WORK/storage-paths-test"
"$WORK/storage-paths-test" "$WORK/path-guards"
"$WORK/transaction-test" "$INPUT" "$WORK/transactions"
c++ -std=c++17 -O2 -DAVB_COMPILATION -I"$AVB" -I"$AVB/crypto" -Wall -Wextra -Werror -Wno-unused-function \
    "$HERE/restart_test.cpp" "$WORK/sha.o" -o "$WORK/restart-test"
"$WORK/restart-test" "$INPUT" "$WORK/restarts"
python3 - "$WORK/transactions/backup/rollback.zip" <<'PY'
import hashlib,sys,zipfile
with zipfile.ZipFile(sys.argv[1]) as z:
    assert z.testzip() is None
    assert hashlib.sha256(z.read('original.img')).hexdigest() == z.read('original.sha256').decode()
    assert b'/tmp/unica-gpu-rollback/restore' in z.read('META-INF/com/google/android/update-binary')
print('PASS recovery ZIP integrity, original-image checksum and updater entry')
PY
"$HERE/profile-test.sh"
python3 "$HERE/policy_test.py" "$ROOT" "$WORK"
bash -n "$HERE/../build.sh" "$ROOT/unica/mods/settings/customize.sh"
echo "GPU test artifacts retained at $WORK"
