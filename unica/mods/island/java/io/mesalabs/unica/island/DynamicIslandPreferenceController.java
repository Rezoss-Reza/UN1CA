// Copyright (c) 2026 UN1CA contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.mesalabs.unica.island;
import android.content.Context;
import android.database.ContentObserver;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import androidx.preference.Preference;
import androidx.preference.PreferenceScreen;
import androidx.preference.TwoStatePreference;
import com.android.settings.core.TogglePreferenceController;
import com.android.settingslib.core.lifecycle.LifecycleObserver;
import com.android.settingslib.core.lifecycle.events.OnStart;
import com.android.settingslib.core.lifecycle.events.OnStop;

public final class DynamicIslandPreferenceController extends TogglePreferenceController
        implements LifecycleObserver, OnStart, OnStop {
    private static final String KEY="unica_dynamic_island";
    private final Context context;
    private Preference preference;
    private final ContentObserver observer=new ContentObserver(new Handler(Looper.getMainLooper())) {
        @Override public void onChange(boolean self) { if (preference!=null) updateState(preference); }
    };
    public DynamicIslandPreferenceController(Context context,String key) { super(context,key); this.context=context; }
    @Override public int getAvailabilityStatus() {
        try {
            Class<?> props=Class.forName("android.os.SystemProperties");
            boolean supported=(Boolean)props.getMethod("getBoolean",String.class,boolean.class)
                    .invoke(null,"ro.unica.dynamic_island",false);
            return supported?0:3;
        } catch (Exception e) { return 3; }
    }
    @Override public boolean isChecked() { return Settings.Global.getInt(context.getContentResolver(),KEY,0)==1; }
    @Override public boolean setChecked(boolean checked) {
        return Settings.Global.putInt(context.getContentResolver(),KEY,checked?1:0);
    }
    @Override public void displayPreference(PreferenceScreen screen) {
        super.displayPreference(screen); preference=screen.findPreference(KEY);
        if (preference!=null) preference.setPersistent(false);
    }
    @Override public void updateState(Preference p) {
        // This controller now gates a submenu; its master switch is inside the activity.
        if (p instanceof TwoStatePreference) ((TwoStatePreference)p).setChecked(isChecked());
    }
    @Override public void onStart() { context.getContentResolver().registerContentObserver(Settings.Global.getUriFor(KEY),false,observer); }
    @Override public void onStop() { context.getContentResolver().unregisterContentObserver(observer); }
}
