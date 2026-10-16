/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.mesalabs.unica.maximum;

import android.content.Context;
import android.content.pm.PackageManager;
import android.util.Log;
import com.android.server.pm.PackageManagerService;

public final class MaximumSuspendPolicy {
    private MaximumSuspendPolicy() {}

    public static boolean isManager(String suspender) {
        return MaximumPolicy.SMART_MANAGER.equals(suspender);
    }

    public static boolean keepProtection(PackageManagerService service, String pkg,
            int caller, int user, boolean stockProtected) {
        if (!stockProtected || !MaximumPolicy.optional(pkg)) return stockProtected;
        try {
            Context context = service.mContext;
            PackageManager pm = context.getPackageManager();
            boolean manager = false;
            String[] packages = pm.getPackagesForUid(caller);
            if (packages != null) for (String name : packages)
                manager |= MaximumPolicy.SMART_MANAGER.equals(name);
            if (!manager || caller / 100000 != user) return true;
            boolean signed = pm.checkSignatures("android", MaximumPolicy.SMART_MANAGER)
                    == PackageManager.SIGNATURE_MATCH;
            return !MaximumPolicy.requestAllowed(pkg, caller, user, manager, signed,
                    MaximumPolicy.fixedForUser(context, pkg, user), MaximumPolicy.active(context, user),
                    MaximumPolicy.secure(context, MaximumPolicy.KNOWN + pkg, user) == 1
                    && MaximumPolicy.secure(context, MaximumPolicy.OFF + pkg, user) == 1);
        } catch (Exception | LinkageError error) {
            Log.w("UnicaMaximum", "Keeping package suspension protection", error);
            return stockProtected;
        }
    }
}
