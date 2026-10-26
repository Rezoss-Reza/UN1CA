#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later
set -euo pipefail
ROOT=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
WORK=$(mktemp -d /tmp/nudge-recall-test.XXXXXX)
javac -source 8 -target 8 -Xlint:-options -d "$WORK" \
    "$ROOT/ssco-java/io/mesalabs/rezoss/ai/RecallPrediction.java" \
    "$ROOT/ssco-java/io/mesalabs/rezoss/ai/RecallSession.java" "$ROOT/tests/RecallInferenceTest.java"
java -cp "$WORK" io.mesalabs.rezoss.ai.RecallInferenceTest
