// SPDX-License-Identifier: GPL-3.0-or-later
package com.android.settings.core;
import android.content.Context;
import androidx.preference.Preference;
import androidx.preference.PreferenceScreen;
public abstract class TogglePreferenceController {
    public TogglePreferenceController(Context context, String key) {}
    public void displayPreference(PreferenceScreen screen) {}
    public void updateState(Preference preference) {}
    public abstract int getAvailabilityStatus();
    public abstract boolean isChecked();
    public abstract boolean setChecked(boolean checked);
    public int getSliceHighlightMenuRes() { return 0; }
}
