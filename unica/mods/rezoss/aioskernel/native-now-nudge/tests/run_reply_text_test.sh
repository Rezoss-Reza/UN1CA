#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later
set -euo pipefail
ROOT=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
WORK=$(mktemp -d /tmp/nudge-reply-text-test.XXXXXX)
javac -source 8 -target 8 -Xlint:-options -d "$WORK" \
    "$ROOT/backend/java/io/mesalabs/rezoss/nudge/ReplyText.java" \
    "$ROOT/backend/java/io/mesalabs/rezoss/nudge/PromptAdapter.java" "$ROOT/tests/ReplyTextTest.java"
java -cp "$WORK" io.mesalabs.rezoss.nudge.ReplyTextTest
