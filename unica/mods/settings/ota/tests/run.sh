#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later
set -euo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
WORK=$(mktemp -d /tmp/unica-ota-tests.XXXXXX)
javac -d "$WORK" "$HERE/../java/io/mesalabs/unica/ota/OtaRules.java" "$HERE/RulesTest.java"
java -cp "$WORK" io.mesalabs.unica.ota.RulesTest
python3 "$HERE/metadata_test.py"
python3 "$HERE/recovery_test.py"
