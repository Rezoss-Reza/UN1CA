/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.mesalabs.unica.quicktiles;

import android.app.*;
import android.content.*;
import android.database.ContentObserver;
import android.net.*;
import android.os.*;
import android.provider.Settings;
import android.service.quicksettings.*;
import android.widget.Toast;

public abstract class QuickTile extends TileService implements SharedPreferences.OnSharedPreferenceChangeListener {
    protected abstract int slot();
    private boolean observing;
    private final ContentObserver observer = new ContentObserver(new Handler(Looper.getMainLooper())) {
        @Override public void onChange(boolean selfChange) { refresh(); }
    };
    @Override public void onStartListening() {
        super.onStartListening();
        if (!observing) {
            for (String key : new String[]{"private_dns_mode", "private_dns_default_mode", "private_dns_specifier", "adb_wifi_enabled", "adb_enabled", "development_settings_enabled"})
                getContentResolver().registerContentObserver(Settings.Global.getUriFor(key), false, observer);
            TileConfig.prefs(this).registerOnSharedPreferenceChangeListener(this);
            observing = true;
        }
        refresh();
    }
    @Override public void onStopListening() { stopObserving(); super.onStopListening(); }
    @Override public void onDestroy() { stopObserving(); super.onDestroy(); }
    private void stopObserving() {
        if (observing) {
            getContentResolver().unregisterContentObserver(observer);
            TileConfig.prefs(this).unregisterOnSharedPreferenceChangeListener(this);
            observing = false;
        }
    }
    @Override public void onSharedPreferenceChanged(SharedPreferences p, String key) { refresh(); }
    private int setting(String key) { return Settings.Global.getInt(getContentResolver(), key, 0); }
    private void put(String key, String value) {
        if (!Settings.Global.putString(getContentResolver(), key, value)) throw new IllegalStateException("The system did not accept the change");
    }
    private boolean restricted(String key) { return getSystemService(UserManager.class).hasUserRestriction(key); }
    private void refresh() {
        Tile tile = getQsTile();
        if (tile == null) return;
        String action = TileConfig.action(this, slot());
        String subtitle = TileConfig.name(action);
        int state = Tile.STATE_INACTIVE;
        if ("dns".equals(action)) {
            String mode = TileConfig.dnsMode(this);
            subtitle = "off".equals(mode) ? "Off" : "hostname".equals(mode) ? "On · Custom provider" : "Auto";
            if (!"off".equals(mode)) state = Tile.STATE_ACTIVE;
        } else if ("wifi_adb".equals(action) || "usb_adb".equals(action)) {
            boolean enabled = setting("wifi_adb".equals(action) ? "adb_wifi_enabled" : "adb_enabled") != 0;
            subtitle = enabled ? "On" : "Off";
            if (enabled) state = Tile.STATE_ACTIVE;
        } else if ("none".equals(action)) subtitle = "Tap to configure";
        tile.setLabel(TileConfig.label(this, slot()));
        tile.setSubtitle(subtitle);
        tile.setContentDescription(TileConfig.label(this, slot()) + ", " + subtitle);
        tile.setIcon(TileConfig.icon(this, slot()));
        tile.setState(state);
        tile.updateTile();
    }
    @Override public void onClick() {
        super.onClick();
        if (isLocked()) unlockAndRun(this::execute); else execute();
    }
    private void open(Intent intent) {
        startActivityAndCollapse(PendingIntent.getActivity(this, slot(), intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
    }
    private void execute() {
        try {
            String action = TileConfig.action(this, slot());
            switch (action) {
                case "dns":
                    if (restricted("disallow_config_private_dns")) throw new IllegalStateException("Private DNS is restricted by your administrator");
                    String next = TileConfig.nextDnsMode(TileConfig.dnsMode(this));
                    if ("hostname".equals(next)) {
                        String host = TileConfig.get(this, slot(), "host", "");
                        if (host.isEmpty()) { open(new Intent(this, ConfigActivity.class).putExtra("slot", slot())); return; }
                        put("private_dns_specifier", TileConfig.hostname(host));
                    }
                    put("private_dns_mode", next);
                    break;
                case "wifi_adb": case "usb_adb":
                    if (restricted(UserManager.DISALLOW_DEBUGGING_FEATURES)) throw new IllegalStateException("Debugging is restricted by your administrator");
                    String key = "wifi_adb".equals(action) ? "adb_wifi_enabled" : "adb_enabled";
                    boolean enable = setting(key) == 0;
                    if (enable && setting("development_settings_enabled") == 0) throw new IllegalStateException("Enable Developer options first");
                    if (enable && "wifi_adb".equals(action)) {
                        ConnectivityManager cm = getSystemService(ConnectivityManager.class);
                        boolean wifi = false;
                        for (Network network : cm.getAllNetworks()) {
                            NetworkCapabilities caps = cm.getNetworkCapabilities(network);
                            if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) wifi = true;
                        }
                        if (!wifi) throw new IllegalStateException("Connect to Wi-Fi first");
                    }
                    put(key, enable ? "1" : "0");
                    break;
                case "restart":
                    int uid = getPackageManager().getApplicationInfo("com.android.systemui", 0).uid;
                    if (uid == android.os.Process.myUid() || uid < android.os.Process.FIRST_APPLICATION_UID) throw new IllegalStateException("Unexpected SystemUI UID; restart cancelled");
                    ActivityManager.class.getMethod("killUid", int.class, String.class).invoke(getSystemService(ActivityManager.class), uid, "UN1CA Quick Tile restart");
                    break;
                case "app": case "preset": open(TileConfig.launchIntent(this, slot())); break;
                case "shortcut": open(new Intent(this, ShortcutActivity.class).putExtra("slot", slot())); break;
                default: open(new Intent(this, ConfigActivity.class).putExtra("slot", slot()));
            }
        } catch (Exception error) {
            String message = error instanceof java.lang.reflect.InvocationTargetException ? "SystemUI restart was not permitted" : error.getMessage();
            Toast.makeText(this, message == null ? "Action unavailable" : message, Toast.LENGTH_LONG).show();
        }
        refresh();
    }
    public static final class Slot1 extends QuickTile { protected int slot() { return 0; } }
    public static final class Slot2 extends QuickTile { protected int slot() { return 1; } }
    public static final class Slot3 extends QuickTile { protected int slot() { return 2; } }
    public static final class Slot4 extends QuickTile { protected int slot() { return 3; } }
    public static final class Slot5 extends QuickTile { protected int slot() { return 4; } }
    public static final class Slot6 extends QuickTile { protected int slot() { return 5; } }
    public static final class Slot7 extends QuickTile { protected int slot() { return 6; } }
    public static final class Slot8 extends QuickTile { protected int slot() { return 7; } }
}
