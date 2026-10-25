#!/usr/bin/env python3
# Copyright (c) 2026 Rezoss
# SPDX-License-Identifier: GPL-3.0-or-later
"""Regression: Android forbids WebView creation in Settings' UID 1000."""
from pathlib import Path
import xml.etree.ElementTree as ET

root = Path(__file__).resolve().parents[1]
settings = (root / 'java/io/mesalabs/unica/keybox/KeyboxActivity.java').read_text()
assert 'new WebView(' not in settings
assert 'android.webkit.' not in settings
assert 'startActivityForResult(new Intent().setClassName(' in settings
assert 'Settings.Secure.putString' in settings

android = '{http://schemas.android.com/apk/res/android}'
manifest = ET.parse(root / 'browser/AndroidManifest.xml').getroot()
assert android + 'sharedUserId' not in manifest.attrib
assert [p.get(android + 'name') for p in manifest.findall('uses-permission')] == ['android.permission.INTERNET']
activity = manifest.find('application/activity')
assert activity.get(android + 'permission') == 'android.permission.WRITE_SECURE_SETTINGS'
browser = (root / 'browser/java/io/mesalabs/unica/keybox/browser/RepositoryActivity.java').read_text()
assert 'getCallingPackage()' in browser
assert 'com.android.settings' in browser
assert 'Settings.Secure' not in browser
assert 'unica_keybox_data' not in browser
assert 'addJavascriptInterface' not in browser
print('Browser boundary tests passed: normal UID, INTERNET only, Settings-only caller, URL-only result')
