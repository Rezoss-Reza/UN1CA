/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.mesalabs.unica.maximum;

import android.app.AlertDialog;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.PersistableBundle;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.widget.CompoundButton;
import android.widget.Toast;
import com.samsung.android.sm.powermode.ebs.data.EbsAppData;
import java.lang.reflect.Method;
import java.util.ArrayList;

/** Stock EBS grouping and switch rendering remain in the original adapter. */
public final class EssentialApps {
    private EssentialApps() {}

    public static void adjust(Context context, EbsAppData data) {
        try {
            if (MaximumPolicy.fixed(context, data.b)) {
                data.d = false;
                data.e = true;
            } else if (MaximumPolicy.optional(data.b) && data.a == MaximumPolicy.ownUser()) {
                boolean known = MaximumPolicy.secure(context, MaximumPolicy.KNOWN + data.b, data.a) == 1;
                if (data.d && !known) return; // Do not promote non-essential alternative apps.
                if (!known) write(context, MaximumPolicy.KNOWN + data.b, true);
                // Keep the original Essential section even when PMS now permits suspension.
                data.d = false;
                data.e = MaximumPolicy.secure(context, MaximumPolicy.OFF + data.b, data.a) != 1;
            }
        } catch (Exception error) {
            Log.w("UnicaMaximum", "Keeping stock essential-app state", error);
        }
    }

    public static void bind(View row, CompoundButton button, EbsAppData data) {
        Context context = row.getContext();
        button.setOnClickListener(null);
        button.setClickable(false); // As in the stock EBS layout; the row handles taps.
        if (data.d) return; // Ordinary All apps rows retain the stock listeners.
        row.setOnClickListener(null); // Clear listeners left by RecyclerView reuse.
        button.setOnCheckedChangeListener(null);
        button.setVisibility(View.GONE);
        row.setClickable(false);
        try {
            if (MaximumPolicy.fixed(context, data.b) || !MaximumPolicy.optional(data.b)
                    || data.a != MaximumPolicy.ownUser()) return;
            button.setVisibility(View.VISIBLE);
            button.setEnabled(true);
            button.setChecked(data.e);
            button.setContentDescription(data.c);
            button.jumpDrawablesToCurrentState();
            View.OnClickListener click = view -> {
                // Samsung's SwitchCompat may already have toggled itself before onClick.
                button.setChecked(data.e);
                if (data.e) {
                    new AlertDialog.Builder(context).setTitle(data.c)
                        .setMessage("Turn off essential access in Maximum power saving? This app may be unavailable and its notifications may stop until you turn it back on or leave Maximum mode. Turning off Phone or Messages can interrupt calls or messages.")
                        .setNegativeButton(android.R.string.cancel, null)
                        .setPositiveButton(android.R.string.ok, (dialog, which) -> change(context, button, data, false))
                        .show();
                } else {
                    change(context, button, data, true);
                }
            };
            button.setOnClickListener(click);
            row.setOnClickListener(click);
        } catch (Exception error) {
            Log.w("UnicaMaximum", "Cannot bind essential switch", error);
            button.setVisibility(View.GONE);
        }
    }

    private static void write(Context context, String key, boolean value) throws Exception {
        int number = value ? 1 : 0;
        if (!Settings.Secure.putInt(context.getContentResolver(), key, number)
                || Settings.Secure.getInt(context.getContentResolver(), key, -1) != number)
            throw new IllegalStateException("Setting was not saved: " + key);
    }

    private static void change(Context context, CompoundButton button, EbsAppData data, boolean on) {
        boolean old = data.e;
        boolean runtimeAttempted = false;
        try {
            if (MaximumPolicy.fixed(context, data.b) || !MaximumPolicy.optional(data.b)
                    || data.a != MaximumPolicy.ownUser()) return;
            boolean active = MaximumPolicy.active(context, data.a);
            // Journal first: an interrupted suspension must remain recoverable on exit.
            if (!on && active) write(context, MaximumPolicy.PENDING + data.b, true);
            write(context, MaximumPolicy.OFF + data.b, !on);
            if (active || (on && MaximumPolicy.secure(context, MaximumPolicy.PENDING + data.b, data.a) == 1)) {
                runtimeAttempted = true;
                suspend(context, data.b, !on, data.a);
                if (on) write(context, MaximumPolicy.PENDING + data.b, false);
            }
            data.e = on;
        } catch (Exception | LinkageError error) {
            Log.e("UnicaMaximum", "Essential-app change failed", error);
            try {
                write(context, MaximumPolicy.OFF + data.b, !old);
                // If a failed OFF request reached PMS, remove only our suspension.
                if (runtimeAttempted && old) {
                    suspend(context, data.b, false, data.a);
                    write(context, MaximumPolicy.PENDING + data.b, false);
                }
            } catch (Exception rollbackError) {
                Log.e("UnicaMaximum", "Restoration pending; journal retained", rollbackError);
            }
            Toast.makeText(context, "Could not change essential access. Android may protect this app. Try again or leave Maximum mode.", Toast.LENGTH_LONG).show();
        }
        button.setChecked(data.e);
    }

    /** Called before the original batch suspension, with its original register count. */
    public static void beforeSuspend(Context context, boolean suspend, String[] packages, int user) {
        if (!suspend || user != MaximumPolicy.ownUser()) return;
        for (String pkg : packages) {
            if (!MaximumPolicy.optional(pkg)) continue;
            try {
                if (!MaximumPolicy.fixed(context, pkg)
                        && MaximumPolicy.secure(context, MaximumPolicy.OFF + pkg, user) == 1)
                    write(context, MaximumPolicy.PENDING + pkg, true);
            } catch (Exception error) {
                // Do not suspend an optional essential without a durable recovery marker.
                throw new IllegalStateException("Could not journal Maximum suspension", error);
            }
        }
    }

    /** Restore even role holders, which Samsung's ordinary exit path excludes. */
    public static void onMode(Context context, int mode) {
        if (mode == 1) return;
        for (String pkg : MaximumPolicy.OPTIONAL) {
            try {
                if (MaximumPolicy.secure(context, MaximumPolicy.PENDING + pkg, MaximumPolicy.ownUser()) == 1) {
                    suspend(context, pkg, false, MaximumPolicy.ownUser());
                    write(context, MaximumPolicy.PENDING + pkg, false);
                }
            } catch (Exception | LinkageError error) {
                Log.e("UnicaMaximum", "Restoration pending for " + pkg, error);
            }
        }
    }

    public static void recoverIfInactive(Context context) {
        try {
            if (!MaximumPolicy.active(context, MaximumPolicy.ownUser())) onMode(context, 0);
        } catch (Exception error) {
            Log.e("UnicaMaximum", "Could not check pending restoration", error);
        }
    }

    private static void suspend(Context context, String pkg, boolean value, int user) throws Exception {
        Object binder = Class.forName("android.app.AppGlobals").getMethod("getPackageManager").invoke(null);
        Class<?> api = Class.forName("android.content.pm.IPackageManager");
        Class<?> dialog = Class.forName("android.content.pm.SuspendDialogInfo");
        Method method = api.getMethod("setPackagesSuspendedAsUser", String[].class, boolean.class,
                PersistableBundle.class, PersistableBundle.class, dialog, int.class, String.class, int.class, int.class);
        Object info = value ? rl.m.e(true) : null;
        Object result = method.invoke(binder, new String[]{pkg}, value, null, null, info, 0,
                context.getPackageName(), MaximumPolicy.ownUser(), user);
        if (!(result instanceof String[]) || ((String[]) result).length != 0)
            throw new IllegalStateException("PackageManager rejected suspension change for " + pkg);
        if (value && !context.getPackageManager().isPackageSuspended(pkg))
            throw new IllegalStateException("PackageManager did not suspend " + pkg);
        ArrayList<String> packages = new ArrayList<>();
        packages.add(pkg);
        new sl.a(context).b(user, value ? "com.android.server.am.MARS_TRIGGER_MPSM_POLICY"
                : "com.android.server.am.MARS_CANCEL_MPSM_POLICY", packages);
    }
}
