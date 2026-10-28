#!/usr/bin/env bash
# Copyright (c) 2026 Rezoss
# SPDX-License-Identifier: GPL-3.0-or-later
set -euo pipefail
SOURCE_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
WORK=$(mktemp -d /tmp/unica-autopif-tests.XXXXXX)
# Android supplies org.json at runtime. Use a pinned host-only copy for these tests.
JSON_JAR="${PIF_TEST_JSON_JAR:-/tmp/unica-pif-test-json-20240303.jar}"
if [[ ! -f "$JSON_JAR" ]]; then
    curl --fail --location --silent --show-error \
        https://repo.maven.apache.org/maven2/org/json/json/20240303/json-20240303.jar \
        -o "$WORK/json.jar"
    JSON_JAR="$WORK/json.jar"
fi
printf '%s  %s\n' 3cf6cd6892e32e2b4c1c39e0f52f5248a2f5b37646fdfbb79a66b46b618414ed "$JSON_JAR" | sha256sum --check --status
javac -encoding UTF-8 -cp "$JSON_JAR" -d "$WORK/classes" \
    "$SOURCE_DIR/java/io/mesalabs/unica/pif/Profile.java" \
    "$SOURCE_DIR/java/io/mesalabs/unica/pif/PixelSource.java" \
    "$SOURCE_DIR/java/io/mesalabs/unica/pif/AutoPif.java" \
    "$SOURCE_DIR/tests/AutoPifTest.java"
java -cp "$WORK/classes:$JSON_JAR" io.mesalabs.unica.pif.AutoPifTest "$@"
echo "Test output retained at $WORK"
