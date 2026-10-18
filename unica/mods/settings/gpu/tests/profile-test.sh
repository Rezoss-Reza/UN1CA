#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later
set -euo pipefail
HERE=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
: "${JSON_JAR:?Set JSON_JAR to a host org.json implementation; see ../README.txt}"
WORK=$(mktemp -d /tmp/unica-gpu-profile-tests.XXXXXX)
javac -encoding UTF-8 -cp "$JSON_JAR" -d "$WORK" "$HERE/../java/io/mesalabs/unica/gpu/GpuProfile.java" "$HERE/../java/io/mesalabs/unica/gpu/StockTables.java" "$HERE/../java/io/mesalabs/unica/gpu/KonaProfileImport.java" "$HERE/GpuProfileTest.java"
java -cp "$JSON_JAR:$WORK" io.mesalabs.unica.gpu.GpuProfileTest "$@"
