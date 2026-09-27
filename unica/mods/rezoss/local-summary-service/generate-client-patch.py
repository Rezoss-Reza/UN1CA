#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Generate additive smali from Java; never edit decoded firmware in place."""
import difflib
from pathlib import Path
import sys

decoded, compiled, output = map(Path, sys.argv[1:])
parts = ["Subject: [PATCH] Connect in-app and notification replies to RezossAI\n\n"]


def edit(relative, transform):
    before = (decoded / relative).read_text()
    after = transform(before)
    assert after != before, relative
    parts.extend(difflib.unified_diff(before.splitlines(True), after.splitlines(True),
                                     fromfile="a/" + relative, tofile="b/" + relative))


def resolver(source):
    marker = ".method public final execute(Landroid/content/Context;Ljava/lang/String;Landroid/view/contentcapture/ContentCaptureSessionId;Lcom/samsung/android/smartsuggestions/service/smartreply/base/Conversation;ZZZ)V"
    start = source.index(marker)
    pos = source.index('    const-string p0, "context"', start)
    source = source[:pos] + '''    invoke-static {p1, p2, p3, p4}, Lio/mesalabs/rezoss/ai/InAppReplies;->start(Landroid/content/Context;Ljava/lang/String;Ljava/lang/Object;Ljava/lang/Object;)Z

    move-result v0

    if-eqz v0, :rezoss_native_inapp

    return-void

    :rezoss_native_inapp
''' + source[pos:]
    start = source.index(".method public final exit(Landroid/content/Context;Landroid/view/contentcapture/ContentCaptureSessionId;Ljava/lang/String;)V")
    pos = source.index('    const-string v0, "context"', start)
    return source[:pos] + '''    invoke-static {p1, p2, p3}, Lio/mesalabs/rezoss/ai/InAppReplies;->exit(Landroid/content/Context;Ljava/lang/Object;Ljava/lang/String;)V

''' + source[pos:]


edit("smali_classes15/com/samsung/android/smartsuggestions/service/smartreply/SmartReplyResolver.smali", resolver)


def package_gate(source):
    start = source.index(".method private final isEnabledPackage(Landroid/content/Context;Landroid/content/ComponentName;)Z")
    pos = source.index("    invoke-static {p1}", start)
    return source[:pos] + '''    invoke-virtual {p2}, Landroid/content/ComponentName;->getPackageName()Ljava/lang/String;

    move-result-object p0

    invoke-static {p0}, Lio/mesalabs/rezoss/ai/InAppReplies;->supports(Ljava/lang/String;)Z

    move-result p0

    if-eqz p0, :rezoss_native_package

    return p0

    :rezoss_native_package
''' + source[pos:]


edit("smali_classes15/com/samsung/android/smartsuggestions/service/deepscreencapture/screenunderstanding/SmartReplyHandler.smali", package_gate)


def notification(source):
    old = "Lcom/samsung/android/smartsuggestions/extservice/services/RezossLocalQwenActions;->suggest("
    assert source.count(old) == 1
    return source.replace(old, "Lio/mesalabs/rezoss/ai/NotificationReplies;->suggest(")


edit("smali_classes14/com/samsung/android/smartsuggestions/extservice/services/CustomTextClassifierService.smali", notification)

def capture_whitelist(source):
    marker = "    invoke-virtual {p0, v0, p1}, Lcom/samsung/android/smartsuggestions/extservice/services/ContentCaptureService;->setContentCaptureWhitelist(Ljava/util/Set;Ljava/util/Set;)V"
    assert source.count(marker) == 1
    return source.replace(marker, """    invoke-static {v0}, Lio/mesalabs/rezoss/ai/CapturePolicy;->packages(Ljava/util/Set;)Ljava/util/Set;

    move-result-object v0

""" + marker)


edit("smali_classes14/com/samsung/android/smartsuggestions/extservice/services/ContentCaptureService.smali", capture_whitelist)


def capture_spec(source):
    marker = "    invoke-virtual {v1, v13}, Lorg/json/JSONObject;->has(Ljava/lang/String;)Z"
    assert source.count(marker) == 1
    return source.replace(marker, """    invoke-static {v1, v13}, Lio/mesalabs/rezoss/ai/CapturePolicy;->inspect(Lorg/json/JSONObject;Ljava/lang/String;)V

""" + marker)


edit("smali_classes15/com/samsung/android/smartsuggestions/service/deepscreencapture/screenunderstanding/ScreenContentManager.smali", capture_spec)

for path in sorted(compiled.rglob("*.smali")):
    relative = "smali_classes16/" + path.relative_to(compiled).as_posix()
    assert not (decoded / relative).exists(), relative
    parts.extend(difflib.unified_diff([], path.read_text().splitlines(True),
                                     fromfile="/dev/null", tofile="b/" + relative))
if output.exists():
    raise SystemExit("Refusing to overwrite an existing patch; back it up and choose a new output.")
output.write_text("".join(parts))
