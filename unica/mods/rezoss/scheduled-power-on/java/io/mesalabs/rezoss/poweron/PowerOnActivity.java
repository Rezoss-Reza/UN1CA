// SPDX-License-Identifier: GPL-3.0-or-later
package io.mesalabs.rezoss.poweron;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.app.TimePickerDialog;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.text.DateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class PowerOnActivity extends Activity {
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private TextView status;
    private Button choose, cancel, refresh;
    private SharedPreferences prefs;
    private boolean busy;
    private interface Work { String run() throws Exception; }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences("schedule", MODE_PRIVATE);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        int pad = (int)(24 * getResources().getDisplayMetrics().density);
        layout.setPadding(pad, pad, pad, pad);
        TextView title = new TextView(this);
        title.setText("Scheduled power on"); title.setTextSize(26); layout.addView(title);
        TextView info = new TextView(this);
        info.setText("Choose when to start your S23 Ultra after you shut it down. "
                + "This is a one-time schedule, up to 7 days ahead. Shut down manually after saving.\n\n"
                + "Experimental: startup from full shutdown still needs device validation. "
                + "Keep enough battery charge and use another alarm for anything essential. "
                + "Your PIN will still be required after startup.\n");
        layout.addView(info);
        status = new TextView(this); layout.addView(status);
        choose = button(layout, "Choose date and time", this::pick);
        cancel = button(layout, "Cancel power-on schedule", () -> new AlertDialog.Builder(this)
                .setMessage("Clear the Samsung power-on alarm?")
                .setNegativeButton("Keep", null).setPositiveButton("Clear", (d,w) -> cancel()).show());
        refresh = button(layout, "Check saved schedule", this::refresh);
        setContentView(layout);
        if (!Build.DEVICE.startsWith("dm3q")) {
            status.setText("This mod supports dm3q (S23 Ultra) only.");
            choose.setEnabled(false); cancel.setEnabled(false); refresh.setEnabled(false);
        } else refresh();
    }
    private Button button(LinearLayout parent, String text, Runnable action) {
        Button b = new Button(this); b.setText(text);
        b.setOnClickListener(v -> { if (!busy) action.run(); }); parent.addView(b); return b;
    }
    private void perform(Work work) {
        if (busy) return;
        busy = true; choose.setEnabled(false); cancel.setEnabled(false); refresh.setEnabled(false);
        status.setText("Checking schedule…");
        worker.execute(() -> {
            String message;
            try { message = work.run(); }
            catch (Exception | LinkageError e) { message = "Unable to confirm schedule: " + e.getMessage(); }
            final String result = message;
            runOnUiThread(() -> {
                busy = false;
                if (isDestroyed()) return;
                status.setText(result); choose.setEnabled(true); cancel.setEnabled(true); refresh.setEnabled(true);
            });
        });
    }
    private String date(long seconds) {
        return DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(new Date(seconds * 1000));
    }
    private String describe() throws Exception {
        long[] s = AlarmHardware.snapshot();
        long target = prefs.getLong("target", 0);
        long offset = prefs.getLong("offset", 0);
        if (prefs.getBoolean("uncertain", false))
            return "The last change did not complete. Cancel the schedule before shutting down.";
        if (target == 0) return "No schedule saved by this app. Existing hardware alarms are left unchanged.";
        if (target <= s[0]) return "Previous schedule: " + date(target)
                + ".\nChoose a new time for another startup. Power-on success is not confirmed by this screen.";
        if (prefs.getInt("boot", -1) != bootCount())
            return "Saved time: " + date(target) + ".\nThe phone restarted after saving. Save the time again before shutting down.";
        if (Math.abs((s[0] - s[1]) - offset) > 10)
            return "The system clock has changed. Choose the date and time again to update the hardware alarm.";
        return "Saved: " + date(target) + "\nSamsung driver accepted the schedule. "
                + "It applies the power-on alarm during shutdown. Hardware programming and startup "
                + "cannot be confirmed while Android is running.";
    }
    private void refresh() { perform(this::describe); }
    private int bootCount() {
        return Settings.Global.getInt(getContentResolver(), Settings.Global.BOOT_COUNT, -1);
    }
    private void pick() {
        Calendar value = Calendar.getInstance(); value.add(Calendar.MINUTE, 5);
        new DatePickerDialog(this, (p,y,m,d) -> {
            value.set(y,m,d);
            new TimePickerDialog(this, (v,h,min) -> {
                value.set(Calendar.HOUR_OF_DAY,h); value.set(Calendar.MINUTE,min);
                value.set(Calendar.SECOND,0); value.set(Calendar.MILLISECOND,0);
                long target = value.getTimeInMillis()/1000;
                new AlertDialog.Builder(this).setTitle("Schedule power on")
                        .setMessage(date(target) + "\n\nThe phone will not shut down automatically.")
                        .setNegativeButton("Back",null)
                        .setPositiveButton("Save", (dialog,which) -> save(target)).show();
            }, value.get(Calendar.HOUR_OF_DAY),value.get(Calendar.MINUTE),
                    android.text.format.DateFormat.is24HourFormat(this)).show();
        },value.get(Calendar.YEAR),value.get(Calendar.MONTH),value.get(Calendar.DAY_OF_MONTH)).show();
    }
    private void markUncertain() throws Exception {
        if (!prefs.edit().putBoolean("uncertain",true).commit())
            throw new Exception("Cannot save schedule state; hardware was not changed.");
    }
    private void save(long target) {
        perform(() -> {
            markUncertain();
            long[] before = AlarmHardware.snapshot();
            AlarmHardware.program(target);
            if (!prefs.edit().putLong("target",target).putLong("offset",before[0]-before[1])
                    .putInt("boot",bootCount())
                    .putBoolean("uncertain",false).commit())
                throw new Exception("Hardware changed, but state could not be saved. Cancel before shutdown.");
            return describe();
        });
    }
    private void cancel() {
        perform(() -> {
            markUncertain(); AlarmHardware.program(0);
            if (!prefs.edit().clear().commit()) throw new Exception("Hardware cleared, but app state could not be saved.");
            return "Samsung driver accepted cancellation. It clears the saved power-on schedule "
                    + "and applies its reserved alarm date during shutdown. Hardware state cannot be read back here.";
        });
    }
    @Override protected void onDestroy() { worker.shutdown(); super.onDestroy(); }
}
