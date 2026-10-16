#!/system/bin/sh
# Copyright (c) 2026 Rezoss
# SPDX-License-Identifier: GPL-3.0-or-later
[ -f /system/etc/init/unica-teesim.rc ] || abort "The ROM-integrated TEE Simulator service is required."
[ "$(getprop ro.product.device)" = "dm3q" ] || abort "This hotfix is for dm3q only."
set_perm_recursive "$MODPATH/system" 0 0 0755 0644
set_perm "$MODPATH/system/bin/unica-teesim-inject" 0 0 0755
