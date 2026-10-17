#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later
# Host-only regression tests; fixtures are never included in the client helper DEX.
set -euo pipefail
ROOT=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
WORK=$(mktemp -d /tmp/nudge-reply-requests-test.XXXXXX)
javac -source 8 -target 8 -Xlint:-options -d "$WORK" \
    "$ROOT/client-java/io/mesalabs/rezoss/ai/ReplyRequests.java" \
    "$ROOT/tests/fixtures/kotlinx/coroutines/"*.java "$ROOT/tests/ReplyRequestsTest.java"
java -cp "$WORK" io.mesalabs.rezoss.ai.ReplyRequestsTest
