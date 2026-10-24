// SPDX-License-Identifier: GPL-3.0-or-later
package io.mesalabs.unica.thermal;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.BroadcastOptions;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import androidx.preference.Preference;
import androidx.preference.PreferenceScreen;
import androidx.preference.TwoStatePreference;
import com.android.settings.core.TogglePreferenceController;
import com.android.settingslib.core.lifecycle.LifecycleObserver;
import com.android.settingslib.core.lifecycle.events.OnStart;
import com.android.settingslib.core.lifecycle.events.OnStop;

/** The switch reflects acknowledged SDHMS state, never a cached Settings value. */
public final class RelaxSdhmsPreferenceController extends TogglePreferenceController
        implements LifecycleObserver, OnStart, OnStop {
    private static final String PACKAGE = "com.sec.android.sdhms";
    private static final ComponentName RECEIVER = new ComponentName(PACKAGE,
            "io.mesalabs.unica.thermal.RelaxPolicyReceiver");
    private final Context context;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private TwoStatePreference preference;
    private AlertDialog dialog;
    private boolean checked, known, busy;
    private int generation;

    public RelaxSdhmsPreferenceController(Context context, String key) {
        super(context, key);
        this.context = context;
    }

    @Override public int getAvailabilityStatus() {
        if (!"dm3q".equals(Build.DEVICE) || Process.myUid() != 1000) return 3;
        try {
            context.getPackageManager().getReceiverInfo(RECEIVER, 0);
            return context.getPackageManager().checkSignatures(context.getPackageName(), PACKAGE)
                    == PackageManager.SIGNATURE_MATCH ? 0 : 3;
        } catch (PackageManager.NameNotFoundException missing) { return 3; }
    }

    @Override public void displayPreference(PreferenceScreen screen) {
        super.displayPreference(screen);
        preference = (TwoStatePreference) screen.findPreference("unica_relax_sdhms");
        if (preference != null) preference.setPersistent(false);
        render("unica_relax_sdhms_checking", false);
    }

    @Override public boolean isChecked() { return checked; }
    @Override public int getSliceHighlightMenuRes() { return 0; }
    @Override public void updateState(Preference pref) {
        super.updateState(pref);
        if (!busy && getAvailabilityStatus() == 0) refresh(++generation);
    }
    @Override public void onStart() {
        if (getAvailabilityStatus() == 0) refresh(++generation);
    }
    @Override public void onStop() {
        generation++;
        busy = false;
        handler.removeCallbacksAndMessages(null);
        if (dialog != null) dialog.dismiss();
        dialog = null;
    }

    @Override public boolean setChecked(boolean enabled) {
        if (!known || busy || enabled == checked || dialog != null) return false;
        if (!enabled) {
            apply(false);
            return false;
        }
        boolean dark = (context.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        int red = Color.parseColor(dark ? "#FF8A80" : "#B3261E");
        SpannableString warning = new SpannableString(text("unica_relax_sdhms_warning"));
        warning.setSpan(new ForegroundColorSpan(red), 0, warning.length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        dialog = new AlertDialog.Builder(context)
                .setTitle(text("unica_relax_sdhms_warning_title"))
                .setMessage(warning)
                .setNegativeButton(android.R.string.cancel, (d, which) -> {})
                .setPositiveButton(text("unica_relax_sdhms_enable"), (d, which) -> apply(true))
                .create();
        dialog.setOnDismissListener(d -> dialog = null);
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(red));
        dialog.show();
        return false;
    }

    private void refresh(int token) {
        if (preference == null) return;
        busy = true;
        render("unica_relax_sdhms_checking", false);
        request(null, token, reply -> {
            busy = false;
            if (reply == null) {
                known = false;
                render("unica_relax_sdhms_unavailable", false);
                return;
            }
            checked = reply.getBoolean("marker");
            known = true;
            boolean settled = reply.getBoolean("ready") && !reply.getBoolean("restarting")
                    && checked == reply.getBoolean("loaded");
            render(settled ? (checked ? "unica_relax_sdhms_on" : "unica_relax_sdhms_off")
                    : "unica_relax_sdhms_pending", true);
        });
    }

    private void apply(boolean enabled) {
        final int token = ++generation;
        busy = true;
        render("unica_relax_sdhms_applying", false);
        request(enabled, token, reply -> {
            if (reply == null) { fail(token); return; }
            int oldPid = reply.getInt("pid");
            long deadline = SystemClock.elapsedRealtime() + 25000;
            boolean needsRestart = reply.getBoolean("restarting");
            poll(enabled, token, oldPid, needsRestart, deadline);
        });
    }

    private void poll(boolean enabled, int token, int oldPid, boolean needsRestart, long deadline) {
        handler.postDelayed(() -> {
            if (token != generation) return;
            request(null, token, reply -> {
                if (reply != null && reply.getBoolean("ready") && !reply.getBoolean("restarting")
                        && reply.getBoolean("marker") == enabled
                        && reply.getBoolean("loaded") == enabled
                        && (!needsRestart || reply.getInt("pid") != oldPid)) {
                    checked = enabled;
                    known = true;
                    busy = false;
                    render(enabled ? "unica_relax_sdhms_on" : "unica_relax_sdhms_off", true);
                } else if (SystemClock.elapsedRealtime() < deadline) {
                    poll(enabled, token, oldPid, needsRestart, deadline);
                } else { fail(token); }
            });
        }, 1000);
    }

    private void fail(int token) {
        // Re-read the actual marker. Do not report success or silently restore
        // a cached switch when the process restart could not be confirmed.
        request(null, token, reply -> {
            known = reply != null;
            if (reply != null) checked = reply.getBoolean("marker");
            busy = false;
            render("unica_relax_sdhms_failed", known);
        });
    }

    private interface Callback { void done(Bundle reply); }
    private void request(Boolean enabled, int token, Callback callback) {
        Intent intent = new Intent(RelaxPolicyReceiver.ACTION).setComponent(RECEIVER);
        if (enabled != null) intent.putExtra("enabled", enabled.booleanValue());
        final boolean[] delivered = {false};
        Runnable timeout = () -> {
            if (!delivered[0] && token == generation) {
                delivered[0] = true;
                callback.done(null);
            }
        };
        handler.postDelayed(timeout, 4000);
        try {
            Bundle options = BroadcastOptions.makeBasic().setShareIdentityEnabled(true).toBundle();
            context.sendOrderedBroadcast(intent, null, options, new BroadcastReceiver() {
                @Override public void onReceive(Context c, Intent i) {
                    if (delivered[0] || token != generation) return;
                    delivered[0] = true;
                    handler.removeCallbacks(timeout);
                    Bundle reply = getResultExtras(false);
                    callback.done(getResultCode() == Activity.RESULT_OK && reply != null
                            && reply.getInt("protocol") == 1 ? reply : null);
                }
            }, handler, Activity.RESULT_CANCELED, null, null);
        } catch (RuntimeException failure) {
            handler.removeCallbacks(timeout);
            if (!delivered[0] && token == generation) {
                delivered[0] = true;
                callback.done(null);
            }
        }
    }

    private String text(String key) {
        return context.getString(context.getResources().getIdentifier(key, "string", "com.android.settings"));
    }
    private void render(String summary, boolean enabled) {
        if (preference == null) return;
        preference.setChecked(checked);
        preference.setEnabled(enabled);
        preference.setSummary(text(summary));
    }
}
