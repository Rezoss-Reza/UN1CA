#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later
set -euo pipefail
: "${SRC_DIR:?Source buildenv.sh dm3q first}"
source "$SRC_DIR/scripts/utils/module_utils.sh"
TEST_WORK=$(mktemp -d /tmp/rezoss-poweron-integration.XXXXXX)
MODPATH="$SRC_DIR/unica/mods/rezoss"
WORK_DIR="$TEST_WORK/target"
mkdir -p "$WORK_DIR/configs" "$WORK_DIR/system/system/etc/selinux" "$WORK_DIR/vendor/etc/selinux"
cat > "$WORK_DIR/configs/fs_config-system" <<'EOF'
system 0 0 755 capabilities=0x0
system/app 0 0 755 capabilities=0x0
system/etc 0 0 755 capabilities=0x0
system/etc/sysconfig 0 0 755 capabilities=0x0
EOF
cat > "$WORK_DIR/configs/file_context-system" <<'EOF'
/system u:object_r:system_file:s0
/system/app u:object_r:system_file:s0
/system/etc u:object_r:system_file:s0
/system/etc/sysconfig u:object_r:system_file:s0
EOF
cp "$FW_DIR/SM-S918B_EUX/system/system/etc/selinux/plat_sepolicy.cil" \
    "$FW_DIR/SM-S918B_EUX/system/system/etc/selinux/plat_sepolicy_and_mapping.sha256" \
    "$WORK_DIR/system/system/etc/selinux/"
cp "$FW_DIR/SM-S918B_EUX/vendor/etc/selinux/vendor_sepolicy.cil" "$WORK_DIR/vendor/etc/selinux/"
source "$MODPATH/scheduled-power-on/integrate.sh"
test -s "$WORK_DIR/system/system/app/RezossPowerOn/RezossPowerOn.apk"
test -s "$WORK_DIR/system/system/app/RezossPowerOn/lib/arm64/librezoss_poweron.so"
test -s "$WORK_DIR/system/system/etc/sysconfig/rezoss-poweron.xml"
grep -Fq 'system/app/RezossPowerOn/RezossPowerOn.apk 0 0 644' "$WORK_DIR/configs/fs_config-system"
grep -Fq 'system/app/RezossPowerOn/lib/arm64/librezoss_poweron.so 0 0 644' "$WORK_DIR/configs/fs_config-system"
grep -Fq 'system_lib_file' "$WORK_DIR/configs/file_context-system"
echo "PASS: actual ROM helper installation and native-library metadata in $TEST_WORK"
