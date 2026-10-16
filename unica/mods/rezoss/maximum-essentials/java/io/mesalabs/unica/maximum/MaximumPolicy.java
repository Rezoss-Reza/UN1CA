/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.mesalabs.unica.maximum;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Process;
import android.provider.Settings;

/** Shared contract between Device Care and the narrowly scoped PMS exception. */
public final class MaximumPolicy {
    public static final String OFF = "unica_maximum_essential_off_";
    public static final String PENDING = "unica_maximum_essential_pending_";
    public static final String KNOWN = "unica_maximum_essential_known_";
    public static final String SMART_MANAGER = "com.samsung.android.sm_cn";
    public static final String[] OPTIONAL = {
        "com.android.vending", "com.google.android.apps.walletnfcrel",
        "com.samsung.android.spay", "com.samsung.android.messaging",
        "com.google.android.apps.messaging", "com.samsung.android.dialer",
        "com.google.android.dialer"
    };

    private MaximumPolicy() {}

    public static boolean optional(String pkg) {
        for (String candidate : OPTIONAL) if (candidate.equals(pkg)) return true;
        return false;
    }

    public static boolean fixed(Context context, String pkg) {
        if (pkg == null || SMART_MANAGER.equals(pkg) || "com.samsung.android.lool".equals(pkg)
                || "com.android.settings".equals(pkg) || "com.sec.android.app.clockpackage".equals(pkg)
                || "com.android.deskclock".equals(pkg) || "com.google.android.deskclock".equals(pkg)) return true;
        Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
        ResolveInfo resolved = context.getPackageManager().resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY);
        return resolved != null && resolved.activityInfo != null && pkg.equals(resolved.activityInfo.packageName);
    }

    public static boolean fixedForUser(Context context, String pkg, int user) throws Exception {
        if (user == ownUser()) return fixed(context, pkg);
        // system_server's base Context belongs to user 0, not necessarily the target user.
        Object handle = android.os.UserHandle.class.getMethod("of", int.class).invoke(null, user);
        Context target = (Context) Context.class.getMethod("createContextAsUser",
                android.os.UserHandle.class, int.class).invoke(context, handle, 0);
        return fixed(target, pkg);
    }

    public static int ownUser() { return Process.myUid() / 100000; }

    public static int secure(Context context, String key, int user) throws Exception {
        if (user == ownUser()) return Settings.Secure.getInt(context.getContentResolver(), key, 0);
        return (Integer) Settings.Secure.class.getMethod("getIntForUser",
                android.content.ContentResolver.class, String.class, int.class, int.class)
                .invoke(null, context.getContentResolver(), key, 0, user);
    }

    public static boolean active(Context context, int user) throws Exception {
        return Settings.Global.getInt(context.getContentResolver(), "low_power", 0) == 1
                && secure(context, "sem_power_mode_ebs_option", user) == 1;
    }

    /** Refuse cross-user requests and every package outside the explicit UI contract. */
    public static boolean requestAllowed(String pkg, int caller, int user, boolean callerIsManager,
            boolean platformSigned, boolean fixed, boolean active, boolean off) {
        int appId = caller % 100000;
        return (appId == 1000 || appId >= 10000) && caller / 100000 == user && callerIsManager && platformSigned
                && optional(pkg) && !fixed && active && off;
    }
}
