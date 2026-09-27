/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.mesalabs.unica.quicktiles;

import android.content.*;
import android.content.pm.*;
import android.graphics.drawable.Icon;
import android.provider.Settings;
import android.provider.AlarmClock;
import android.provider.MediaStore;
import java.net.IDN;

public final class TileConfig {
    public static final int COUNT = 8;
    static final String[] ACTIONS = {"none", "dns", "restart", "wifi_adb", "usb_adb", "app", "shortcut", "preset"};
    static final String[] NAMES = {"Not configured", "Private DNS", "Restart SystemUI", "Wireless debugging", "USB debugging", "Open app", "App shortcut", "Predefined action"};
    static final String[] PRESETS = {"Camera", "Record video", "Dial a number", "New message", "Set alarm", "Wi-Fi settings", "Bluetooth settings", "Wireless debugging settings"};
    static final Class<?>[] SERVICES = {QuickTile.Slot1.class, QuickTile.Slot2.class, QuickTile.Slot3.class, QuickTile.Slot4.class, QuickTile.Slot5.class, QuickTile.Slot6.class, QuickTile.Slot7.class, QuickTile.Slot8.class};
    static SharedPreferences prefs(Context c) { return c.getSharedPreferences("unica_quick_tiles", Context.MODE_PRIVATE); }
    static String get(Context c, int slot, String key, String fallback) { return prefs(c).getString(slot + "." + key, fallback); }
    static String action(Context c, int slot) { return get(c, slot, "action", "none"); }
    static String name(String action) {
        for (int i = 0; i < ACTIONS.length; i++) if (ACTIONS[i].equals(action)) return NAMES[i];
        return NAMES[0];
    }
    static String label(Context c, int slot) { return get(c, slot, "label", "UN1CA Tile " + (slot + 1)); }
    static ComponentName component(Context c, int slot) { return new ComponentName(c, SERVICES[slot]); }
    static String hostname(String value) {
        String host = IDN.toASCII(value.trim(), IDN.USE_STD3_ASCII_RULES).toLowerCase(java.util.Locale.ROOT);
        if (host.length() > 253 || host.indexOf('.') < 1 || host.endsWith(".") || host.matches("[0-9.]+")) throw new IllegalArgumentException("Enter a DNS provider hostname, such as dns.google");
        for (String part : host.split("\\.", -1)) if (part.isEmpty() || part.length() > 63 || part.startsWith("-") || part.endsWith("-")) throw new IllegalArgumentException("Invalid DNS provider hostname");
        return host;
    }
    static String dnsMode(Context c) {
        String mode = Settings.Global.getString(c.getContentResolver(), "private_dns_mode");
        if (mode == null || mode.isEmpty()) mode = Settings.Global.getString(c.getContentResolver(), "private_dns_default_mode");
        return mode == null || mode.isEmpty() ? "opportunistic" : mode;
    }
    static String nextDnsMode(String mode) { return "off".equals(mode) ? "opportunistic" : "opportunistic".equals(mode) ? "hostname" : "off"; }
    static Icon icon(Context c, int slot) {
        String action = action(c, slot);
        String selected = get(c, slot, "icon", "auto");
        if ("auto".equals(selected) && ("app".equals(action) || "shortcut".equals(action))) {
            try {
                ApplicationInfo info = c.getPackageManager().getApplicationInfo(get(c, slot, "package", ""), 0);
                if (info.icon != 0) return Icon.createWithResource(info.packageName, info.icon);
            } catch (PackageManager.NameNotFoundException ignored) { }
        }
        String key = "auto".equals(selected) ? action : selected;
        String resource = "unica_qt_" + ("dns".equals(key) ? "dns" : "restart".equals(key) ? "restart" : "wifi_adb".equals(key) ? "wifi" : "usb_adb".equals(key) ? "usb" : "app");
        return Icon.createWithResource(c, c.getResources().getIdentifier(resource, "drawable", c.getPackageName()));
    }
    static Intent preset(int index) {
        switch (index) {
            case 0: return new Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA);
            case 1: return new Intent(MediaStore.INTENT_ACTION_VIDEO_CAMERA);
            case 2: return new Intent(Intent.ACTION_DIAL);
            case 3: return new Intent(Intent.ACTION_SENDTO, android.net.Uri.parse("smsto:"));
            case 4: return new Intent(AlarmClock.ACTION_SET_ALARM);
            case 5: return new Intent(Settings.ACTION_WIFI_SETTINGS);
            case 6: return new Intent(Settings.ACTION_BLUETOOTH_SETTINGS);
            case 7: return new Intent("android.settings.WIRELESS_DEBUGGING_SETTINGS");
            default: throw new IllegalArgumentException("Unknown predefined action");
        }
    }
    static Intent launchIntent(Context c, int slot) {
        Intent intent;
        if ("app".equals(action(c, slot))) intent = c.getPackageManager().getLaunchIntentForPackage(get(c, slot, "package", ""));
        else intent = preset(Integer.parseInt(get(c, slot, "preset", "0")));
        if (intent == null) throw new IllegalStateException("This app is no longer available");
        ResolveInfo resolved = c.getPackageManager().resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY);
        if (resolved == null || !resolved.activityInfo.exported || !resolved.activityInfo.enabled) throw new IllegalStateException("This action is not available");
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return intent;
    }
    public static boolean routeLongPress(android.app.Activity activity) {
        ComponentName component = activity.getIntent().getParcelableExtra("android.intent.extra.COMPONENT_NAME", ComponentName.class);
        if (component == null) return false;
        for (int i = 0; i < COUNT; i++) {
            if (component.equals(component(activity, i))) {
                activity.startActivity(new Intent(activity, ConfigActivity.class).putExtra("slot", i));
                activity.finish();
                return true;
            }
        }
        return false;
    }
    private TileConfig() { }
}
