/* SPDX-License-Identifier: GPL-3.0-or-later */
import io.mesalabs.unica.maximum.MaximumPolicy;

public final class MaximumPolicyTest {
    private static int assertions;
    private static void check(boolean expected, String pkg, int uid, int user,
            boolean manager, boolean signed, boolean fixed, boolean active, boolean off) {
        boolean actual = MaximumPolicy.requestAllowed(pkg, uid, user, manager, signed, fixed, active, off);
        if (actual != expected) throw new AssertionError(pkg + " uid=" + uid + " user=" + user);
        assertions++;
    }
    public static void main(String[] args) {
        for (String pkg : MaximumPolicy.OPTIONAL) {
            check(true, pkg, 10000, 0, true, true, false, true, true);
            check(false, pkg, 10000, 0, true, true, false, false, true);
            check(false, pkg, 10000, 0, true, true, false, true, false);
            check(false, pkg, 10000, 0, false, true, false, true, true);
            check(false, pkg, 10000, 0, true, false, false, true, true);
            check(false, pkg, 10000, 0, true, true, true, true, true);
            check(true, pkg, 1000, 0, true, true, false, true, true);
            check(false, pkg, 2000, 0, true, true, false, true, true);
            check(false, pkg, 10000, 10, true, true, false, true, true);
            check(true, pkg, 1010000, 10, true, true, false, true, true);
        }
        for (String pkg : new String[]{"com.android.settings", "com.sec.android.app.clockpackage",
                "com.samsung.android.sm_cn", "com.teslacoilsw.launcher", "android",
                "com.android.systemui", "com.google.android.gms", "com.example.app", "", null})
            check(false, pkg, 10000, 0, true, true, false, true, true);
        System.out.println("PASS " + assertions + " Maximum-mode policy assertions");
    }
}
