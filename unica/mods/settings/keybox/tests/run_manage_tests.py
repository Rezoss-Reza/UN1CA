#!/usr/bin/env python3
# Copyright (c) 2026 Rezoss
# SPDX-License-Identifier: GPL-3.0-or-later
"""Host tests using a real org.json JAR and a build_backend.py output; no device access."""
import argparse
import os
from pathlib import Path
import subprocess
import tempfile

p = argparse.ArgumentParser(description=__doc__)
p.add_argument('--backend-build', type=Path, required=True)
p.add_argument('--json-jar', type=Path, required=True)
a = p.parse_args()
root = Path(__file__).resolve().parents[1]
backend = a.backend_build.resolve()
json_jar = a.json_jar.resolve()
work = Path(tempfile.mkdtemp(prefix='manage-keybox-tests-', dir='/tmp'))
java = root / 'java/io/mesalabs/unica/keybox'

def run(*args):
    subprocess.run([str(v) for v in args], check=True)

run('javac', '-encoding', 'UTF-8', '-cp', json_jar, '-d', work,
    *(java / f'{name}.java' for name in ('Keybox', 'Roots', 'Profiles', 'Diagnostics')),
    root / 'tests/ManageKeyboxTest.java')
run('java', '-cp', os.pathsep.join(map(str, (work, json_jar))),
    'io.mesalabs.unica.keybox.ManageKeyboxTest')
sdk = Path(os.environ.get('ANDROID_HOME', str(Path.home() / 'Android/Sdk')))
android = sdk / 'platforms/android-37.0/android.jar'
jars = list((backend / 'jars').glob('*.jar'))
cp = os.pathsep.join(map(str, [backend / 'java/classes', json_jar,
                               backend / 'java/stubs.jar', android, *jars]))
run('java', '-cp', os.pathsep.join(map(str, jars)),
    'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler', '-no-stdlib', '-no-reflect',
    '-jvm-target', '17', '-classpath', cp, '-d', work,
    root / 'tests/RomProfilesTest.kt')
run('java', '-cp', str(work) + os.pathsep + cp, 'org.matrix.teesim.RomProfilesTestKt')
print(f'Host test output: {work}')
