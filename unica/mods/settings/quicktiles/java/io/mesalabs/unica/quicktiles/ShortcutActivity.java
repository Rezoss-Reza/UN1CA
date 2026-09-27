/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.mesalabs.unica.quicktiles;

import android.app.Activity;
import android.app.KeyguardManager;
import android.content.pm.LauncherApps;
import android.os.Bundle;
import android.os.Process;
import android.widget.Toast;

/** Private trampoline: only a tile-created immutable PendingIntent can launch it. */
public final class ShortcutActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        int slot = getIntent().getIntExtra("slot", -1);
        try {
            if (slot < 0 || slot >= TileConfig.COUNT || !"shortcut".equals(TileConfig.action(this, slot)) || getSystemService(KeyguardManager.class).isKeyguardLocked()) return;
            getSystemService(LauncherApps.class).startShortcut(TileConfig.get(this, slot, "package", ""), TileConfig.get(this, slot, "shortcut", ""), null, null, Process.myUserHandle());
        } catch (Exception error) {
            Toast.makeText(this, "This shortcut is no longer available. Choose it again in Quick Tiles.", Toast.LENGTH_LONG).show();
        } finally { finish(); }
    }
}
