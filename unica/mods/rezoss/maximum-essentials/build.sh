#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later
set -euo pipefail
SOURCE_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
SDK="${ANDROID_HOME:-$HOME/Android/Sdk}"
ANDROID_JAR="${ANDROID_JAR:-$SDK/platforms/android-37.0/android.jar}"
D8="${D8:-$SDK/build-tools/37.0.0/d8}"
if (( $# != 2 )); then
    echo "Usage: $0 CLEAN_SMARTMANAGER_DECODE CLEAN_SERVICES_DECODE" >&2
    exit 2
fi
WORK=$(mktemp -d /tmp/unica-maximum-build.XXXXXX)
mkdir -p "$WORK/stubs/com/samsung/android/sm/powermode/ebs/data" "$WORK/stubs/rl" \
    "$WORK/stubs/sl" "$WORK/stubs/com/android/server/pm" "$WORK/classes" "$WORK/dex"
# Compile-time declarations only; never ship these replacement classes.
cat > "$WORK/stubs/com/samsung/android/sm/powermode/ebs/data/EbsAppData.java" <<'JAVA'
package com.samsung.android.sm.powermode.ebs.data;
public final class EbsAppData { public int a; public String b, c; public boolean d, e; }
JAVA
cat > "$WORK/stubs/rl/m.java" <<'JAVA'
package rl;
public final class m { public static Object e(boolean value) { return null; } }
JAVA
cat > "$WORK/stubs/sl/a.java" <<'JAVA'
package sl;
public final class a {
    public a(android.content.Context context) {}
    public void b(int user, String action, java.util.ArrayList<String> packages) {}
}
JAVA
cat > "$WORK/stubs/com/android/server/pm/PackageManagerService.java" <<'JAVA'
package com.android.server.pm;
public final class PackageManagerService { public android.content.Context mContext; }
JAVA
mapfile -t SOURCES < <(find "$SOURCE_DIR/java" "$WORK/stubs" -name '*.java' -type f | sort)
javac -encoding UTF-8 -source 8 -target 8 -Xlint:-options -classpath "$ANDROID_JAR" \
    -d "$WORK/classes" "${SOURCES[@]}"
jar cf "$WORK/helpers.jar" -C "$WORK/classes" io
"$D8" --min-api 35 --lib "$ANDROID_JAR" --output "$WORK/dex" "$WORK/helpers.jar"
baksmali disassemble --api 35 "$WORK/dex/classes.dex" -o "$WORK/smali"
python3 "$SOURCE_DIR/generate-patches.py" "$1" "$2" "$WORK/smali"
javac -encoding UTF-8 -classpath "$WORK/classes:$ANDROID_JAR" -d "$WORK/classes" \
    "$SOURCE_DIR/tests/MaximumPolicyTest.java"
java -cp "$WORK/classes:$ANDROID_JAR" MaximumPolicyTest
echo "Compiler output retained at $WORK"
