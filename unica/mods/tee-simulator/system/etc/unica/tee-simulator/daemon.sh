#!/system/bin/sh
# Copyright (c) 2026 Rezoss
# SPDX-License-Identifier: GPL-3.0-or-later

umask 077
# An explicit local recovery switch. Keep init's service alive without touching keystore.
if [ -e /data/misc/unica-teesim/disable ]; then
    # Keep the service idle without repeatedly starting app_process or touching keystore.
    exec /system/bin/sleep infinity
fi
export CLASSPATH=/system/framework/unica-teesim.jar
exec /system/bin/app_process64 /system/bin --nice-name=unica-teesim org.matrix.teesim.RomApp /system/etc/unica/tee-simulator
