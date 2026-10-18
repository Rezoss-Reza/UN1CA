#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later
set -euo pipefail
ROOT=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
WORK=$(mktemp -d /tmp/rezoss-poweron-tests.XXXXXX)
c++ -std=c++17 -Wall -Wextra -Werror "$ROOT/tests/alarm_test.cpp" -o "$WORK/alarm_test"
TZ=Pacific/Honolulu "$WORK/alarm_test"
TZ=Europe/Paris "$WORK/alarm_test"
JAVA="$ROOT/java/io/mesalabs/rezoss/poweron"
javac -d "$WORK/classes" "$JAVA/ScheduleRules.java" "$JAVA/ScheduleEngine.java" "$ROOT/tests/ScheduleEngineTest.java"
java -cp "$WORK/classes" io.mesalabs.rezoss.poweron.ScheduleEngineTest
python3 "$ROOT/tests/policy_test.py"
python3 "$ROOT/tests/ui_integration_test.py"
bash -n "$ROOT/build.sh" "$ROOT/integrate.sh"
echo "Test workspace: $WORK"
