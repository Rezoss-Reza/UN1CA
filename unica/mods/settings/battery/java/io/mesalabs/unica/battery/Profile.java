/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.mesalabs.unica.battery;

import org.json.*;
import java.util.*;

/** Portable user choices only. Hardware paths and rollback snapshots are never imported. */
public final class Profile {
    public static JSONObject empty() throws JSONException {
        return new JSONObject().put("format", "unica-battery").put("version", 1)
                .put("enabled", false).put("preset", "Balanced").put("controls", new JSONObject())
                .put("packages", new JSONArray()).put("bucket", "45").put("exemptions", new JSONArray());
    }
    public static JSONObject validate(JSONObject input) throws JSONException {
        if (!"unica-battery".equals(input.optString("format")) || input.optInt("version") != 1)
            throw new JSONException("Unsupported battery profile format/version");
        Set<String> fields = new HashSet<>(Arrays.asList("format", "version", "enabled", "preset", "controls", "packages", "bucket", "exemptions"));
        for (Iterator<String> it = input.keys(); it.hasNext();) if (!fields.contains(it.next())) throw new JSONException("Unknown profile field");
        if (!(input.get("enabled") instanceof Boolean)) throw new JSONException("Invalid enable switch");
        JSONObject out = empty();
        out.put("enabled", input.getBoolean("enabled"));
        String preset = input.getString("preset");
        if (!Arrays.asList("Balanced", "Efficiency", "NOSLEN", "Stingy", "Custom").contains(preset)) throw new JSONException("Invalid preset");
        out.put("preset", preset);
        JSONObject controls = input.getJSONObject("controls"), clean = new JSONObject();
        if (controls.length() > 32) throw new JSONException("Too many controls");
        for (Iterator<String> it = controls.keys(); it.hasNext();) {
            String k = it.next(), v = controls.getString(k);
            if (!k.matches("wifi_pm|wifi_wakeup|aspm|swappiness|cached|freezer|gpu|refresh|power_saver|sync|data_saver|background|brightness_mode|brightness|timeout|aod|wifi_scan|ble_scan|mobile_always|window_animation|transition_animation|animator|night_mode|cpu_policy[0-9]{1,3}")) throw new JSONException("Unknown control: " + k);
            boolean valid;
            switch (k) {
                case "background": valid = Arrays.asList("unrestricted", "restricted").contains(v); break;
                case "power_saver": case "sync": case "data_saver": case "brightness_mode":
                case "aod": case "wifi_scan": case "ble_scan": case "mobile_always":
                    valid = Arrays.asList("0", "1").contains(v); break;
                case "window_animation": case "transition_animation": case "animator":
                    valid = Arrays.asList("0", "0.5", "1").contains(v); break;
                case "night_mode": valid = Arrays.asList("0", "1", "2", "3").contains(v); break;
                case "brightness": valid = v.matches("[0-9]{1,3}") && Integer.parseInt(v)>=1 && Integer.parseInt(v)<=255; break;
                case "timeout": valid = Arrays.asList("15000", "30000", "60000", "120000").contains(v); break;
                case "wifi_pm": valid = Arrays.asList("auto", "on").contains(v); break;
                case "wifi_wakeup": valid = Arrays.asList("enabled", "disabled").contains(v); break;
                case "aspm": valid = Arrays.asList("default", "performance", "powersave", "powersupersave").contains(v); break;
                case "freezer": valid = Arrays.asList("device_default", "enabled", "disabled").contains(v); break;
                default:
                    valid = v.matches("[0-9]{1,10}");
                    if (valid) {
                        long n = Long.parseLong(v);
                        valid = k.equals("swappiness") ? n <= 200 : k.equals("cached") ? n >= 32 && n <= 4096
                            : k.equals("refresh") ? n >= 24 && n <= 240 : n > 0 && n <= 4000000000L;
                    }
            }
            if (!valid) throw new JSONException("Invalid value for " + k);
            clean.put(k, v);
        }
        out.put("controls", clean);
        String bucket = input.getString("bucket");
        if (!Arrays.asList("10", "20", "30", "40", "45").contains(bucket)) throw new JSONException("Invalid standby bucket");
        out.put("bucket", bucket);
        for (String field : new String[]{"packages", "exemptions"}) {
            JSONArray a = input.getJSONArray(field), b = new JSONArray(); Set<String> seen = new HashSet<>();
            if (a.length() > 100) throw new JSONException("Select at most 100 apps");
            for (int i = 0; i < a.length(); i++) {
                String p = a.getString(i);
                if (!p.matches("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+") || p.length() > 200) throw new JSONException("Invalid package name");
                if (seen.add(p)) b.put(p);
            }
            out.put(field, b);
        }
        return out;
    }
    private static boolean contains(JSONArray values,String value) throws JSONException {
        for(int i=0;i<values.length();i++)if(value.equals(values.getString(i)))return true;
        return false;
    }
    public static JSONObject preset(String name, JSONArray capabilities) throws JSONException {
        JSONObject p = empty(); p.put("preset", name); JSONObject c = p.getJSONObject("controls");
        if (name.equals("Stingy")) {
            // Device-specific ceilings; never guess hardware frequencies or lower active floors.
            for (int i=0;i<capabilities.length();i++) {
                JSONObject d=capabilities.getJSONObject(i);
                if(!d.optBoolean("supported"))continue;
                String id=d.getString("id"), choice=null;
                JSONArray values=d.getJSONArray("values");
                if(id.startsWith("cpu_") || id.equals("gpu") || id.equals("refresh")) {
                    long minimum=d.optLong("minimum",id.equals("refresh")?0:-1), best=Long.MAX_VALUE;
                    if(minimum<0)continue; // Old/unreadable worker metadata: do not guess a usable floor.
                    for(int j=0;j<values.length();j++) {
                        long n=Long.parseLong(values.getString(j));
                        if(n>=minimum && n<best){best=n;choice=values.getString(j);}
                    }
                } else if(id.equals("aspm")) {
                    for(String candidate:new String[]{"powersupersave","powersave"})
                        if(contains(values,candidate)){choice=candidate;break;}
                } else {
                    switch(id) {
                        case "wifi_pm": choice="auto"; break;
                        case "wifi_wakeup": choice="disabled"; break;
                        case "freezer": choice="enabled"; break;
                        case "background": choice="restricted"; break;
                        case "power_saver": case "data_saver": choice="1"; break;
                        case "night_mode": choice="2"; break;
                        case "brightness": choice="8"; break;
                        case "timeout": choice="15000"; break;
                        case "sync": case "brightness_mode": case "aod": case "wifi_scan":
                        case "ble_scan": case "mobile_always": case "window_animation":
                        case "transition_animation": case "animator": choice="0"; break;
                        default: break; // Preserve memory tuning: killing caches is not a power policy.
                    }
                }
                if(choice!=null && contains(values,choice))c.put(id,choice);
            }
        } else if (name.equals("NOSLEN")) {
            c.put("wifi_pm", "on").put("wifi_wakeup", "disabled").put("aspm", "performance").put("swappiness", "60").put("cached", "32");
            p.put("packages", new JSONArray(Arrays.asList("com.samsung.android.mcfds", "com.sec.android.diagmonagent", "com.samsung.android.da.daagent")));
        } else if (name.equals("Efficiency")) {
            JSONObject fastest = null; long max = 0;
            for (int i = 0; i < capabilities.length(); i++) {
                JSONObject d = capabilities.getJSONObject(i);
                if (d.getString("id").startsWith("cpu_") && d.optBoolean("supported")) {
                    long n = Long.parseLong(d.optString("current", "0"));
                    if (n > max) { max = n; fastest = d; }
                }
            }
            if (fastest != null) {
                JSONArray values = fastest.getJSONArray("values"); String choice = null;
                for (int i = 0; i < values.length(); i++) {
                    long n = Long.parseLong(values.getString(i));
                    if (n <= max * 85 / 100) choice = values.getString(i);
                }
                if (choice != null) c.put(fastest.getString("id"), choice);
            }
        } else if (!name.equals("Balanced")) throw new JSONException("Unknown preset");
        return p;
    }
}
