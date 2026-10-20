#!/usr/bin/env python3
# Copyright (c) 2026 Rezoss
# SPDX-License-Identifier: GPL-3.0-or-later
"""Host tests use temporary public-property fixtures, never real device areas."""
from pathlib import Path
import subprocess
import tempfile
import sys
import os
import shutil
import importlib.util
sys.dont_write_bytecode=True
ROOT=Path(__file__).resolve().parents[1]
def run(*args): subprocess.run([str(a) for a in args],check=True)
with tempfile.TemporaryDirectory(prefix='unica-pif-test-') as td:
    work=Path(td); areas=work/'areas'; areas.mkdir()
    spec=importlib.util.spec_from_file_location('pif_build',ROOT/'build_native.py')
    module=importlib.util.module_from_spec(spec); spec.loader.exec_module(module)
    (work/'defaults.hpp').write_text(module.defaults_header())
    flags=['-I'+str(work),'-std=c++20','-O1','-g','-fsanitize=undefined','-fno-sanitize-recover=all',
           '-I'+str(ROOT/'source'),'-I'+str(ROOT/'tests/include')]
    run('g++',*flags,ROOT/'tests/profile_test.cpp','-o',work/'profile')
    run(work/'profile')
    # Keep vendored source untouched: substitute the fixture directory in a temporary TU.
    vendor=(ROOT/'source/vendor/property_virtualizer.cpp').read_text()
    vendor=vendor.replace('"/dev/__properties__/"','"'+str(areas)+'/"')
    temp=work/'virtualizer.cpp'; temp.write_text(vendor)
    run('g++',*flags,'-I'+str(ROOT/'source/vendor'),temp,ROOT/'tests/virtualizer_test.cpp','-o',work/'virtualizer')
    for case in ['success','rollback','invalid','oversize']:
        run(work/'virtualizer',case,areas)
        print('PASS native mapping '+case)
    print('PASS profile defaults, identity expansion, explicit overrides, invalid inputs and disabled features')

    # Execute the actual Java startup guard and JNI Build bridge in a host JVM.
    # Android property access is mocked; no device or host system properties are changed.
    java_home=Path(shutil.which('javac')).resolve().parents[1]
    run('g++','-std=c++20','-O1','-fPIC','-shared',
        '-I'+str(ROOT/'source'),'-I'+str(ROOT/'tests/include'),'-I'+str(work),
        '-I'+str(java_home/'include'),'-I'+str(java_home/'include/linux'),
        ROOT/'source/bridge.cpp',ROOT/'tests/jni_fixture.cpp','-o',work/'libunica_pif.so')
    classes=work/'java'; classes.mkdir()
    run('javac','--release','17','-d',classes,
        *sorted((ROOT/'tests/java').rglob('*.java')),
        ROOT/'source/java/io/mesalabs/unica/PifProcessHooks.java')
    cases=[
        ('GMS Build bridge','com.google.android.gms.unstable',{},True,True),
        ('parent guard','zygote',{},False,False),
        ('unrelated app guard','com.example.app',{},False,False),
        ('master disabled','com.google.android.gms.unstable',{'persist.sys.unica.pif':'false'},False,False),
        ('Vending default off','com.android.vending',{},False,False),
        ('Vending opt in','com.android.vending',{'persist.sys.pif.vending_build':'true'},True,True),
        ('property failure fallback','com.google.android.gms.unstable',{'persist.sys.pif.spoof_props':'true'},False,False),
        ('Build disabled','com.google.android.gms.unstable',{'persist.sys.pif.spoof_build':'false'},True,False),
    ]
    for label,process,overrides,active,build in cases:
        env=dict(os.environ)
        env.update({'PIF_TEST_PROCESS':process,'persist.sys.pif.spoof_props':'false'})
        env.update(overrides)
        subprocess.run(['java','-Xcheck:jni','-Djava.library.path='+str(work),'-cp',str(classes),
            'io.mesalabs.unica.StartupTest',str(active).lower(),str(build).lower()],check=True,env=env)
        print('PASS JNI '+label)
