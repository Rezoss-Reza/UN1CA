// SPDX-License-Identifier: GPL-3.0-or-later
package io.mesalabs.unica.portrom;

import android.content.Context;
import android.os.SemSystemProperties;
import android.widget.Toast;
import androidx.preference.Preference;
import androidx.preference.PreferenceScreen;
import androidx.preference.TwoStatePreference;
import com.android.settings.core.TogglePreferenceController;
import java.io.File;

/** Selection is persistent; the marker proves which directory this boot uses. */
public final class PortRomAudioPreferenceController extends TogglePreferenceController {
    private static final String PROPERTY = "persist.sys.unica.portrom_audio";
    private final Context context;
    private Preference preference;

    public PortRomAudioPreferenceController(Context context, String key) {
        super(context, key);
        this.context = context;
    }

    @Override public int getAvailabilityStatus() {
        return new File("/system/etc/unica/portrom-audio/audio/.portrom-v1.0.2").isFile()
                && new File("/system/etc/init/unica_portrom_audio.rc").isFile() ? 0 : 3;
    }

    @Override public boolean isChecked() {
        return SemSystemProperties.getBoolean(PROPERTY, false);
    }

    @Override public int getSliceHighlightMenuRes() { return 0; }

    @Override public void displayPreference(PreferenceScreen screen) {
        super.displayPreference(screen);
        preference = screen.findPreference("unica_portrom_audio");
        if (preference != null) {
            preference.setPersistent(false);
            updateState(preference);
        }
    }

    @Override public void updateState(Preference pref) {
        super.updateState(pref);
        if (pref instanceof TwoStatePreference) {
            ((TwoStatePreference) pref).setChecked(isChecked());
        }
        boolean active = new File("/system/media/audio/.portrom-v1.0.2").isFile();
        String summary = isChecked() ? (active ? "active" : "pending_on")
                : (active ? "pending_off" : "off");
        pref.setSummary(text("unica_portrom_audio_" + summary));
    }

    @Override public boolean setChecked(boolean checked) {
        if (getAvailabilityStatus() != 0) return false;
        try {
            SemSystemProperties.set(PROPERTY, Boolean.toString(checked));
            if (isChecked() != checked) throw new IllegalStateException("Selection not saved");
        } catch (RuntimeException failure) {
            Toast.makeText(context, text("unica_portrom_audio_error"), Toast.LENGTH_LONG).show();
            return false;
        }
        if (preference != null) updateState(preference);
        return true;
    }

    private String text(String name) {
        int id = context.getResources().getIdentifier(name, "string", context.getPackageName());
        return id == 0 ? "PortROM audio: restart to apply" : context.getString(id);
    }
}
