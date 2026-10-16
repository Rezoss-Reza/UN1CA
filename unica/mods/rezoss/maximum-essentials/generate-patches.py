#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Generate exact-input unified patches without editing either decoded tree."""
import difflib
from pathlib import Path
import re
import sys
from datetime import datetime

HERE = Path(__file__).resolve().parent
MOD = HERE.parent
HELPER = "Lio/mesalabs/unica/maximum/EssentialApps;"
POLICY = "Lio/mesalabs/unica/maximum/MaximumSuspendPolicy;"


def replace_once(text, old, new):
    if text.count(old) != 1:
        raise ValueError(f"Expected one anchor, got {text.count(old)}: {old[:100]!r}")
    return text.replace(old, new, 1)


def emit(destination, subject, changes):
    parts = [f"Subject: [PATCH] {subject}\n\n"]
    for name, before, after in changes:
        parts.extend(difflib.unified_diff(before.splitlines(True), after.splitlines(True),
                     fromfile=f"a/{name}", tofile=f"b/{name}", n=4))
    destination.parent.mkdir(parents=True, exist_ok=True)
    if destination.exists():
        backup = destination.with_name(destination.name + ".bak." + datetime.now().strftime("%Y%m%d_%H%M%S_%f"))
        backup.write_bytes(destination.read_bytes())
    destination.write_text("".join(line if line.strip() else "\n" for part in parts for line in part.splitlines(True)))


def main():
    apk, services, helpers = map(Path, sys.argv[1:])
    changes = []
    path = "smali/rl/c.smali"
    before = (apk / path).read_text()
    after = replace_once(before, "    :goto_4\n    return-object v0", f"    :goto_4\n    invoke-static {{v3, v0}}, {HELPER}->adjust(Landroid/content/Context;Lcom/samsung/android/sm/powermode/ebs/data/EbsAppData;)V\n\n    return-object v0")
    changes.append((path, before, after))

    path = "smali/vl/b.smali"
    before = (apk / path).read_text()
    after = replace_once(before, "    :cond_15\n    return-void", f"    :cond_15\n    invoke-virtual {{v4}}, Lvl/a;->v()Lcom/samsung/android/sm/common/view/RoundedCornerRelativeLayout;\n\n    move-result-object v0\n\n    invoke-virtual {{v4}}, Lvl/a;->x()Landroidx/appcompat/widget/SwitchCompat;\n\n    move-result-object v1\n\n    invoke-static {{v0, v1, v5}}, {HELPER}->bind(Landroid/view/View;Landroid/widget/CompoundButton;Lcom/samsung/android/sm/powermode/ebs/data/EbsAppData;)V\n\n    return-void")
    changes.append((path, before, after))

    path = "smali/rl/m.smali"
    before = (apk / path).read_text()
    old = ".method public final g(Z[Ljava/lang/String;I)V\n    .locals 4\n"
    after = replace_once(before, old, old + f"\n    invoke-virtual {{p0}}, Lrl/m;->a()Landroid/content/Context;\n\n    move-result-object v0\n\n    invoke-static {{v0, p1, p2, p3}}, {HELPER}->beforeSuspend(Landroid/content/Context;Z[Ljava/lang/String;I)V\n")
    changes.append((path, before, after))

    path = "smali/rl/a.smali"
    before = (apk / path).read_text()
    old = ".method public final a(I)V\n    .locals 12\n"
    after = replace_once(before, old, old + f"\n    iget-object v0, p0, Lrl/a;->a:Lvv/e;\n\n    invoke-interface {{v0}}, Lvv/e;->getValue()Ljava/lang/Object;\n\n    move-result-object v0\n\n    check-cast v0, Lrl/m;\n\n    invoke-virtual {{v0}}, Lrl/m;->a()Landroid/content/Context;\n\n    move-result-object v0\n\n    invoke-static {{v0, p1}}, {HELPER}->onMode(Landroid/content/Context;I)V\n")
    changes.append((path, before, after))

    path = "smali/com/samsung/android/sm/powermode/ebs/service/EbsInitService.smali"
    before = (apk / path).read_text()
    old = ".method public final a(Landroid/content/Intent;)V\n    .locals 9\n"
    after = replace_once(before, old, old + f"\n    invoke-static {{p0}}, {HELPER}->recoverIfInactive(Landroid/content/Context;)V\n")
    changes.append((path, before, after))

    for file in sorted((helpers / "io/mesalabs/unica/maximum").glob("*.smali")):
        if not file.name.startswith("MaximumSuspendPolicy"):
            changes.append(("smali/" + str(file.relative_to(helpers)), "", file.read_text()))
    emit(MOD / "smali/system/priv-app/SmartManagerCN/SmartManagerCN.apk/0001-Add-optional-Maximum-essential-app-switches.patch",
         "Add optional Maximum essential app switches", changes)

    path = "smali_classes2/com/android/server/pm/SuspendPackageHelper.smali"
    before = (services / path).read_text()
    # Restrict edits to this one method and leave its locals, catches and labels intact.
    method = re.search(r"(?ms)^\.method public final canSuspendPackageForUser\(.*?^\.end method", before)
    if method is None:
        raise ValueError("Missing canSuspendPackageForUser")
    body = method.group()
    call = f"\n    invoke-static {{v8, v10, v3, v2, v9}}, {POLICY}->keepProtection(Lcom/android/server/pm/PackageManagerService;Ljava/lang/String;IIZ)Z\n\n    move-result v9\n"
    for anchor in [
        "    invoke-virtual {v10, v4}, Ljava/lang/String;->equals(Ljava/lang/Object;)Z\n\n    move-result v9\n",
        "    invoke-virtual {v10, v12}, Ljava/lang/String;->equals(Ljava/lang/Object;)Z\n\n    move-result v9\n",
        "    invoke-virtual {v8, v1, v10, v3}, Lcom/android/server/pm/PackageManagerService;->isRequiredSystemPackageThatCallerCannotControl(Lcom/android/server/pm/Computer;Ljava/lang/String;I)Z\n\n    move-result v9\n",
    ]:
        body = replace_once(body, anchor, anchor + call)
    # App-op exemption uses v0 and the loop uses v1/v2 for different values here.
    # Do not bypass it: it also protects privileged background services outside this UI.
    # Preserve the original method for queries and all other callers. The clone is
    # reachable only from setPackagesSuspended with Smart Manager's UserPackage.
    body = body.replace(" canSuspendPackageForUser(", " canSuspendSelectedMaximumPackagesForUser(", 1)
    after = before + "\n" + body + "\n"
    old = "    invoke-virtual {v0, v1, v2, v9, v5}, Lcom/android/server/pm/SuspendPackageHelper;->canSuspendPackageForUser(Lcom/android/server/pm/Computer;[Ljava/lang/String;II)[Z\n\n    move-result-object v11"
    new = f"""    iget-object v11, v4, Landroid/content/pm/UserPackage;->packageName:Ljava/lang/String;

    invoke-static {{v11}}, {POLICY}->isManager(Ljava/lang/String;)Z

    move-result v11

    if-eqz v11, :unica_stock_suspend_check

    invoke-virtual {{v0, v1, v2, v9, v5}}, Lcom/android/server/pm/SuspendPackageHelper;->canSuspendSelectedMaximumPackagesForUser(Lcom/android/server/pm/Computer;[Ljava/lang/String;II)[Z

    move-result-object v11

    goto :goto_1

    :unica_stock_suspend_check
{old}"""
    after = replace_once(after, old, new)
    service_changes = [(path, before, after)]
    for name in ["MaximumPolicy.smali", "MaximumSuspendPolicy.smali"]:
        file = helpers / "io/mesalabs/unica/maximum" / name
        service_changes.append(("smali_classes2/" + str(file.relative_to(helpers)), "", file.read_text()))
    emit(MOD / "smali/system/framework/services.jar/0004-Allow-selected-Maximum-essential-app-suspension.patch",
         "Allow selected Maximum essential app suspension from Smart Manager", service_changes)


if __name__ == "__main__":
    main()
