/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.mesalabs.unica.quicktiles;

import android.app.*;
import android.content.*;
import android.content.pm.*;
import android.os.Bundle;
import android.os.Process;
import android.service.quicksettings.TileService;
import android.view.*;
import android.widget.*;
import java.util.*;

public final class ConfigActivity extends Activity {
    private LinearLayout page;
    private int slot = -1;
    private String action, targetPackage, shortcut, preset, selectedIcon;
    private EditText label, host;
    private Button actionButton, targetButton, iconButton;
    private TextView actionHint;
    private final java.util.concurrent.ExecutorService worker = java.util.concurrent.Executors.newSingleThreadExecutor();
    private int generation;
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        int requested = getIntent().getIntExtra("slot", -1);
        ComponentName component = getIntent().getParcelableExtra("android.intent.extra.COMPONENT_NAME", ComponentName.class);
        if (component != null) for (int i = 0; i < TileConfig.COUNT; i++) if (component.equals(TileConfig.component(this, i))) requested = i;
        if (state != null) requested = state.getInt("slot", requested);
        if (requested >= 0 && requested < TileConfig.COUNT) {
            edit(requested);
            if (state != null) {
                action = state.getString("action", action); targetPackage = state.getString("package", targetPackage);
                shortcut = state.getString("shortcut", shortcut); preset = state.getString("preset", preset); selectedIcon = state.getString("icon", selectedIcon);
                label.setText(state.getString("label", label.getText().toString())); host.setText(state.getString("host", host.getText().toString())); updateEditor();
            }
        } else overview();
    }
    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state); state.putInt("slot", slot);
        if (slot >= 0) {
            state.putString("action", action); state.putString("package", targetPackage); state.putString("shortcut", shortcut);
            state.putString("preset", preset); state.putString("icon", selectedIcon); state.putString("label", label.getText().toString()); state.putString("host", host.getText().toString());
        }
    }
    @Override protected void onDestroy() { generation++; worker.shutdownNow(); super.onDestroy(); }
    private int dp(int value) { return (int) (value * getResources().getDisplayMetrics().density); }
    private void layout(String title) {
        generation++;
        setTitle(title);
        if (getActionBar() != null) getActionBar().setDisplayHomeAsUpEnabled(true);
        ScrollView scroll = new ScrollView(this);
        page = new LinearLayout(this); page.setOrientation(LinearLayout.VERTICAL); page.setPadding(dp(20), dp(12), dp(20), dp(24));
        scroll.setFillViewport(true); scroll.addView(page); setContentView(scroll);
        scroll.setOnApplyWindowInsetsListener((v, insets) -> {
            android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom); return insets;
        });
    }
    private void text(String value) {
        TextView view = new TextView(this); view.setText(value); view.setTextSize(16); view.setPadding(0, dp(10), 0, dp(10)); page.addView(view);
    }
    private Button button(String value, View.OnClickListener listener) {
        Button view = new Button(this); view.setText(value); view.setAllCaps(false); view.setOnClickListener(listener); page.addView(view); return view;
    }
    private EditText input(String hint, String value) {
        EditText view = new EditText(this); view.setHint(hint); view.setContentDescription(hint); view.setSingleLine(true); view.setText(value); page.addView(view); return view;
    }
    private void overview() {
        slot = -1; layout("Quick Tiles");
        text("Assign one action to each tile, then add it to Quick Settings. Hold a tile to edit it.");
        for (int i = 0; i < TileConfig.COUNT; i++) {
            final int index = i;
            button((i + 1) + ". " + TileConfig.label(this, i) + "\n" + TileConfig.name(TileConfig.action(this, i)), v -> edit(index));
        }
    }
    private void edit(int index) {
        slot = index; action = TileConfig.action(this, slot); targetPackage = TileConfig.get(this, slot, "package", "");
        shortcut = TileConfig.get(this, slot, "shortcut", ""); preset = TileConfig.get(this, slot, "preset", "0"); selectedIcon = TileConfig.get(this, slot, "icon", "auto");
        layout("UN1CA Tile " + (slot + 1));
        text("One tap runs the selected action. Changes take effect after Save.");
        label = input("Tile label", TileConfig.label(this, slot));
        actionButton = button("Action", v -> new AlertDialog.Builder(this).setTitle("Choose an action").setItems(TileConfig.NAMES, (d, which) -> {
            generation++; targetButton.setEnabled(true); action = TileConfig.ACTIONS[which]; targetPackage = ""; shortcut = "";
            label.setText("none".equals(action) ? "UN1CA Tile " + (slot + 1) : TileConfig.name(action)); updateEditor();
        }).show());
        actionHint = new TextView(this); actionHint.setPadding(0, dp(8), 0, dp(8)); page.addView(actionHint);
        String existingHost = android.provider.Settings.Global.getString(getContentResolver(), "private_dns_specifier");
        host = input("Private DNS provider hostname", TileConfig.get(this, slot, "host", existingHost == null ? "" : existingHost));
        host.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_URI);
        targetButton = button("Choose target", v -> {
            if ("preset".equals(action)) new AlertDialog.Builder(this).setTitle("Predefined action").setItems(TileConfig.PRESETS, (d, which) -> {
                preset = Integer.toString(which); label.setText(TileConfig.PRESETS[which]); updateEditor();
            }).show(); else chooseApp();
        });
        iconButton = button("Icon", v -> new AlertDialog.Builder(this).setTitle("Tile icon").setItems(new String[]{"Automatic", "DNS", "Restart", "Wireless", "USB", "App"}, (d, which) -> {
            selectedIcon = new String[]{"auto", "dns", "restart", "wifi_adb", "usb_adb", "app"}[which]; updateEditor();
        }).show());
        button("Save", v -> save(false));
        button("Save and add to Quick Settings", v -> save(true));
        button("Cancel", v -> overview());
        button("Reset this slot", v -> new AlertDialog.Builder(this).setMessage("Clear this tile's action and label?").setNegativeButton("Cancel", null).setPositiveButton("Reset", (d, which) -> {
            SharedPreferences.Editor editor = TileConfig.prefs(this).edit();
            for (String key : new String[]{"action", "label", "host", "package", "shortcut", "preset", "icon"}) editor.remove(slot + "." + key);
            editor.apply(); TileService.requestListeningState(this, TileConfig.component(this, slot)); overview();
        }).show());
        updateEditor();
    }
    private void updateEditor() {
        actionButton.setText("Action: " + TileConfig.name(action));
        actionHint.setText("dns".equals(action) ? "Tap to cycle Off → Auto → saved provider. The provider must support Private DNS."
                : "restart".equals(action) ? "Restarts the status bar and Quick Settings. The screen may briefly go blank."
                : "wifi_adb".equals(action) ? "Requires Developer options and Wi-Fi. Pair your computer in Wireless debugging settings first."
                : "usb_adb".equals(action) ? "Requires Developer options. Turning this off disconnects USB debugging."
                : "shortcut".equals(action) ? "Choose a shortcut published by an installed app. Available shortcuts can change when the app is updated."
                : "");
        host.setVisibility("dns".equals(action) ? View.VISIBLE : View.GONE);
        targetButton.setVisibility(("app".equals(action) || "shortcut".equals(action) || "preset".equals(action)) ? View.VISIBLE : View.GONE);
        targetButton.setText("preset".equals(action) ? TileConfig.PRESETS[Integer.parseInt(preset)] : targetPackage.isEmpty() ? "Choose app" : targetPackage + (shortcut.isEmpty() ? "" : " · " + shortcut));
        iconButton.setText("Icon: " + ("auto".equals(selectedIcon) ? "Automatic" : TileConfig.name(selectedIcon)));
    }
    private void chooseApp() {
        final int request = generation;
        targetButton.setEnabled(false); targetButton.setText("Loading apps…");
        worker.execute(() -> {
            List<LauncherActivityInfo> apps;
            try { apps = getSystemService(LauncherApps.class).getActivityList(null, Process.myUserHandle()); }
            catch (RuntimeException error) {
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed() || request != generation) return;
                    targetButton.setEnabled(true); updateEditor(); message("Unable to load installed apps");
                });
                return;
            }
            Map<String, LauncherActivityInfo> unique = new TreeMap<>();
            for (LauncherActivityInfo app : apps) unique.putIfAbsent(app.getComponentName().getPackageName(), app);
            List<LauncherActivityInfo> sorted = new ArrayList<>(unique.values());
            sorted.sort((a, b) -> a.getLabel().toString().compareToIgnoreCase(b.getLabel().toString()));
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed() || request != generation) return;
                targetButton.setEnabled(true); updateEditor();
                String[] labels = new String[sorted.size()];
                for (int i = 0; i < labels.length; i++) labels[i] = sorted.get(i).getLabel() + "\n" + sorted.get(i).getComponentName().getPackageName();
                new AlertDialog.Builder(this).setTitle("Choose an installed app").setItems(labels, (d, which) -> {
                    LauncherActivityInfo app = sorted.get(which);
                    if ("shortcut".equals(action)) chooseShortcut(app.getComponentName().getPackageName());
                    else { targetPackage = app.getComponentName().getPackageName(); label.setText(app.getLabel()); updateEditor(); }
                }).setNegativeButton("Cancel", null).show();
            });
        });
    }
    private void chooseShortcut(String pkg) {
        try {
            LauncherApps launcher = getSystemService(LauncherApps.class);
            LauncherApps.ShortcutQuery query = new LauncherApps.ShortcutQuery().setPackage(pkg).setQueryFlags(LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST | LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC | LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED);
            List<ShortcutInfo> shortcuts = launcher.getShortcuts(query, Process.myUserHandle());
            if (shortcuts == null) shortcuts = new ArrayList<>();
            List<ShortcutInfo> enabled = new ArrayList<>();
            for (ShortcutInfo info : shortcuts) if (info.isEnabled()) enabled.add(info);
            if (enabled.isEmpty()) { message("This app has no available shortcuts. You can assign Open app instead."); return; }
            String[] labels = new String[enabled.size()];
            for (int i = 0; i < labels.length; i++) labels[i] = String.valueOf(enabled.get(i).getShortLabel());
            new AlertDialog.Builder(this).setTitle("Choose an app shortcut").setItems(labels, (d, which) -> {
                targetPackage = pkg; shortcut = enabled.get(which).getId(); label.setText(enabled.get(which).getShortLabel()); updateEditor();
            }).setNegativeButton("Cancel", null).show();
        } catch (RuntimeException error) { message("Shortcut access is unavailable for this app."); }
    }
    private void save(boolean add) {
        try {
            String title = label.getText().toString().trim();
            if (title.isEmpty()) throw new IllegalArgumentException("Enter a tile label");
            if (title.length() > 60) throw new IllegalArgumentException("Keep the label under 61 characters");
            String provider = "dns".equals(action) ? TileConfig.hostname(host.getText().toString()) : host.getText().toString().trim();
            if (("app".equals(action) || "shortcut".equals(action)) && targetPackage.isEmpty()) throw new IllegalArgumentException("Choose an app first");
            if ("shortcut".equals(action) && shortcut.isEmpty()) throw new IllegalArgumentException("Choose an app shortcut first");
            if ("preset".equals(action) && TileConfig.preset(Integer.parseInt(preset)).resolveActivity(getPackageManager()) == null) throw new IllegalArgumentException("No installed app supports this action");
            SharedPreferences.Editor editor = TileConfig.prefs(this).edit();
            String[] keys = {"action", "label", "host", "package", "shortcut", "preset", "icon"};
            String[] values = {action, title, provider, targetPackage, shortcut, preset, selectedIcon};
            for (int i = 0; i < keys.length; i++) editor.putString(slot + "." + keys[i], values[i]);
            editor.apply(); TileService.requestListeningState(this, TileConfig.component(this, slot));
            if (add) {
                getSystemService(android.app.StatusBarManager.class).requestAddTileService(TileConfig.component(this, slot), title, TileConfig.icon(this, slot), getMainExecutor(), result -> {
                    if (result == android.app.StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED || result == android.app.StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED) message("Tile is in Quick Settings");
                    else message("Configuration saved. You can also add the tile using Quick Settings → Edit.");
                });
            } else { Toast.makeText(this, "Tile saved", Toast.LENGTH_SHORT).show(); overview(); }
        } catch (Exception error) { message(error.getMessage() == null ? "Unable to save tile" : error.getMessage()); }
    }
    private void message(String value) { if (!isFinishing() && !isDestroyed()) new AlertDialog.Builder(this).setMessage(value).setPositiveButton("OK", null).show(); }
    @Override public boolean onOptionsItemSelected(MenuItem item) { if (item.getItemId() == android.R.id.home) { if (slot >= 0) overview(); else finish(); return true; } return super.onOptionsItemSelected(item); }
}
