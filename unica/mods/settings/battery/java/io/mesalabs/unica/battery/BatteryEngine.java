/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.mesalabs.unica.battery;

import android.os.Looper;
import android.content.Context;
import android.hardware.display.DisplayManager;
import android.view.Display;
import org.json.*;
import java.io.*;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.*;

/** Runs as Settings using its platform permissions; kernel writes use the native init worker. */
public class BatteryEngine {
    final Context context;
    final File DIR, JOURNAL, ACTIVE;
    BatteryEngine(Context context) {
        this.context=context;
        DIR=context==null?new File("/invalid/battery-test"):new File(context.getFilesDir(),"unica-battery");
        JOURNAL=new File(DIR,"original.json");ACTIVE=new File(DIR,"profile.json");
    }
    final LinkedHashMap<String, JSONObject> specs = new LinkedHashMap<>();
    final List<String> notes = new ArrayList<>();
    JSONObject journal;
    String bootId;
    static String read(File f) throws IOException {
        // procfs can return EOF after a one-byte probe: always start with a bulk read.
        try (FileInputStream in = new FileInputStream(f); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192]; int count;
            while ((count = in.read(buffer)) != -1) { out.write(buffer, 0, count); if (out.size() > 1048576) throw new IOException("File too large"); }
            return out.toString("UTF-8").trim();
        }
    }
    static void atomic(File f, String s) throws IOException {
        File tmp = new File(f.getPath() + ".tmp");
        try (FileOutputStream o = new FileOutputStream(tmp)) { o.write(s.getBytes(StandardCharsets.UTF_8)); o.getFD().sync(); }
        Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }
    static android.os.IBinder service(String name)throws Exception {
        return (android.os.IBinder)Class.forName("android.os.ServiceManager").getMethod("getService",String.class).invoke(null,name);
    }
    static Object idle(String method,Class<?>[] types,Object... args)throws Exception {
        Class<?> api=Class.forName("android.os.IDeviceIdleController");
        Object manager=Class.forName("android.os.IDeviceIdleController$Stub").getMethod("asInterface",android.os.IBinder.class).invoke(null,service("deviceidle"));
        return api.getMethod(method,types).invoke(manager,args);
    }
    static Object config(String method,Class<?>[] types,Object... args)throws Exception {
        return Class.forName("android.provider.DeviceConfig").getMethod(method,types).invoke(null,args);
    }
    static String dumpActivity(String section)throws Exception {
        android.os.ParcelFileDescriptor[] pipe=android.os.ParcelFileDescriptor.createPipe();
        ByteArrayOutputStream data=new ByteArrayOutputStream();
        Thread reader=new Thread(()->{try(InputStream in=new android.os.ParcelFileDescriptor.AutoCloseInputStream(pipe[0])){byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1)if(data.size()<1048576)data.write(b,0,n);}catch(IOException ignored){}});
        reader.start();
        try {service("activity").dumpAsync(pipe[1].getFileDescriptor(),new String[]{section});}
        finally {pipe[1].close();}
        reader.join(5000);if(reader.isAlive()){pipe[0].close();throw new IOException("Activity status timed out");}
        return data.toString("UTF-8");
    }
    static JSONArray array(Collection<String> strings) { return new JSONArray(strings); }
    void add(String id, String label, String help, String kind, String target, Collection<String> values) throws Exception {
        JSONObject d = new JSONObject().put("id", id).put("label", label).put("help", help).put("kind", kind).put("target", target).put("values", array(values));
        try {
            String value = get(d);
            boolean supported = true;
            d.put("supported", supported && !values.isEmpty()).put("current", value);
        } catch (Exception e) { d.put("supported", false).put("current", "Unavailable"); }
        specs.put(id, d);
    }
    static List<String> range(int low, int high) { List<String> a = new ArrayList<>(); for (int i=low;i<=high;i++) a.add(""+i); return a; }
    static List<String> frequencies(String file) {
        TreeSet<Long> set = new TreeSet<>();
        try { for (String s : read(new File(file)).split("\\s+")) { long n=Long.parseLong(s); if(n>0) set.add(n); } } catch (Exception ignored) {}
        List<String> out = new ArrayList<>(); for(long n:set) out.add(""+n); return out;
    }
    static Object binderCall(String serviceName,String apiName,String method,Class<?>[] types,Object... args)throws Exception {
        Class<?> api=Class.forName(apiName);
        Object manager=Class.forName(apiName+"$Stub").getMethod("asInterface",android.os.IBinder.class).invoke(null,service(serviceName));
        return api.getMethod(method,types).invoke(manager,args);
    }
    Object network(String method,Class<?>[] types,Object... args)throws Exception {
        Class<?> api=Class.forName("android.net.NetworkPolicyManager");
        return api.getMethod(method,types).invoke(context.getSystemService("netpolicy"),args);
    }
    int appUid(JSONObject d)throws Exception {
        int uid=context.getPackageManager().getApplicationInfo(d.getString("target"),0).uid;
        if(uid<10000 || uid!=d.getInt("uid"))throw new IOException("App identity changed; refusing UID policy write");
        return uid;
    }
    static boolean autoCandidate(String pkg,int uid,boolean system,boolean launchable) {
        return uid>=10000 && (!system || launchable || pkg.equals("com.android.vending") || pkg.equals("com.sec.android.app.samsungapps"));
    }
    List<String> backgroundTargets()throws Exception {
        android.content.pm.PackageManager pm=context.getPackageManager();
        Set<String> protectedApps=new HashSet<>(Arrays.asList("com.android.settings","com.android.systemui","com.google.android.gms","com.android.phone"));
        android.content.Intent home=new android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_HOME);
        android.content.pm.ResolveInfo launcher=pm.resolveActivity(home,android.content.pm.PackageManager.MATCH_DEFAULT_ONLY);
        if(launcher!=null && launcher.activityInfo!=null)protectedApps.add(launcher.activityInfo.packageName);
        android.view.inputmethod.InputMethodManager imm=context.getSystemService(android.view.inputmethod.InputMethodManager.class);
        for(android.view.inputmethod.InputMethodInfo keyboard:imm.getEnabledInputMethodList())protectedApps.add(keyboard.getPackageName());
        android.telecom.TelecomManager telecom=context.getSystemService(android.telecom.TelecomManager.class);
        String dialer=telecom.getDefaultDialerPackage();if(dialer!=null)protectedApps.add(dialer);
        Set<Integer> protectedUids=new HashSet<>();
        List<android.content.pm.ApplicationInfo> apps=pm.getInstalledApplications(0);
        for(android.content.pm.ApplicationInfo app:apps)
            if(protectedApps.contains(app.packageName)||!autoCandidate(app.packageName,app.uid,(app.flags&android.content.pm.ApplicationInfo.FLAG_SYSTEM)!=0,pm.getLaunchIntentForPackage(app.packageName)!=null))protectedUids.add(app.uid);
        List<String> result=new ArrayList<>();
        for(android.content.pm.ApplicationInfo app:apps)if(!protectedUids.contains(app.uid))result.add(app.packageName);
        Collections.sort(result);return result;
    }
    JSONObject packageDescriptor(String kind,String pkg)throws Exception {
        if(pkg.equals("com.android.settings") || pkg.equals("com.android.systemui") || pkg.equals("com.google.android.gms"))throw new IOException("Essential service protected");
        int uid=context.getPackageManager().getApplicationInfo(pkg,0).uid;
        JSONObject d=new JSONObject().put("kind",kind).put("target",pkg).put("label",pkg).put("uid",uid);
        if(kind.equals("bg_run") || kind.equals("bg_any"))d.put("op",kind.equals("bg_run")?63:70);
        return d;
    }
    void discover() throws Exception {
        try {
            JSONArray kernel=BatteryTransport.request("list","","").getJSONArray("controls");
            for(int i=0;i<kernel.length();i++) {
                JSONObject d=kernel.getJSONObject(i);String id=d.getString("id");
                String label,help;
                if(id.equals("wifi_pm")){label="Wi-Fi runtime power";help="Auto allows runtime sleep. On can bypass a faulty suspend/resume loop, but may increase drain.";}
                else if(id.equals("wifi_wakeup")){label="Wi-Fi wakeup";help="Disabling wake events may delay Wi-Fi notifications or background communication.";}
                else if(id.equals("aspm")){label="PCIe link power";help="Power-saving states may reduce link consumption. Performance disables them; aggressive savings can expose driver instability.";}
                else if(id.equals("swappiness")){label="Swappiness";help="Balances ZRAM work against reclaim and app reloads. A lower value does not guarantee lower power consumption.";}
                else if(id.equals("gpu")){label="GPU ceiling";help="Lower graphics clocks can reduce gaming power at the cost of frame rate. Little benefit for idle drain.";}
                else {label="CPU ceiling · "+id.substring(4);help="A lower ceiling trades peak speed for potentially lower power. Longer task execution can cancel the saving.";}
                d.put("label",label).put("help",help).put("kind","kernel").put("target",id);specs.put(id,d);
            }
        } catch(Exception e) {notes.add("Kernel controls unavailable: "+e.getMessage());}
        String activity;
        try { activity = dumpActivity("settings"); } catch (Exception e) { activity=""; notes.add("Cached-process effective limit unavailable"); }
        int cached=-1; Matcher m=Pattern.compile("CUR_MAX_CACHED_PROCESSES=(\\d+)").matcher(activity); if(m.find()) cached=Integer.parseInt(m.group(1));
        add("cached", "Cached-process limit", "More caching can avoid costly app reloads. Fewer cached processes is not inherently more efficient. Adjustable range: 32–4096.", "device_config", "activity_manager/max_cached_processes", range(32,4096));
        specs.get("cached").put("effective", cached < 0 ? "Unknown" : ""+cached);
        add("freezer", "Cached-app freezer", "Freezing eligible cached apps reduces their CPU activity. Device default preserves Android's policy; changes may need a reboot.", "global", "cached_apps_freezer", Arrays.asList("device_default", "enabled", "disabled"));
        String optimizer;
        try { optimizer=dumpActivity("cao"); } catch (Exception e) { optimizer=""; }
        Matcher freezer=Pattern.compile("(?im)^.*(?:mUseFreezer|use_freezer|freezer enabled|freezer_enabled).*$").matcher(optimizer);
        specs.get("freezer").put("effective", freezer.find()?freezer.group().trim():"Runtime freezer state not reported; no change made by Balanced");
        List<String> rates=new ArrayList<>();
        try {
            Context ctx=context;
            Display display=ctx.getSystemService(DisplayManager.class).getDisplay(Display.DEFAULT_DISPLAY);
            TreeSet<Integer> hz=new TreeSet<>(); for(Display.Mode mode:display.getSupportedModes()) hz.add(Math.round(mode.getRefreshRate()));
            for(int n:hz) if(n>=24 && n<=240) rates.add(""+n);
        } catch(Throwable e) { notes.add("Display modes unavailable; refresh-rate control disabled"); }
        add("refresh", "Display refresh-rate ceiling", "Optional. Lower refresh rates trade smoothness for lower display/rendering power. Samsung's display policy may override this request.", "system", "peak_refresh_rate", rates);
        add("power_saver","Android Battery Saver","Requests Android's low-power policy. Charging or Samsung policy can switch it off.","power","",Arrays.asList("0","1"));
        add("sync","Automatic account sync","Off stops Android sync adapters. Apps with their own network schedulers require background restrictions too.","sync","",Arrays.asList("0","1"));
        add("data_saver","Data Saver","Restricts background data on metered networks; Wi-Fi is not necessarily metered.","data_saver","",Arrays.asList("0","1"));
        add("background","Aggressive app restrictions","Restricts downloaded apps, launchable preinstalled apps and app stores, including notifications, jobs, background services and metered background data. Removes their user-added Doze exemptions. Protects system services, launcher, keyboards and dialer. Reapply after installing apps. Foreground services and OS exemptions can still run.","policy","",Arrays.asList("unrestricted","restricted"));
        add("brightness_mode","Adaptive brightness","Manual mode prevents automatic brightness increases. Review brightness before going outdoors.","system","screen_brightness_mode",Arrays.asList("0","1"));
        add("brightness","Manual brightness","Stingy selects 8 (captured from the device brightness setting) on Android's legacy 1–255 setting scale; this is not a measured panel luminance percentage.","system","screen_brightness",range(1,255));
        add("timeout","Screen timeout","A shorter timeout reduces unattended screen-on time. Apps holding the screen awake can override it.","system","screen_off_timeout",Arrays.asList("15000","30000","60000","120000"));
        add("aod","Always On Display","Off avoids ambient display activity.","system","aod_mode",Arrays.asList("0","1"));
        add("wifi_scan","Wi-Fi scanning while off","Disables always-available Wi-Fi scans; reduces location accuracy.","global","wifi_scan_always_enabled",Arrays.asList("0","1"));
        add("ble_scan","Bluetooth scanning while off","Disables always-available BLE scans; can impair location and nearby-device discovery.","global","ble_scan_always_enabled",Arrays.asList("0","1"));
        add("mobile_always","Keep mobile data active on Wi-Fi","Off reduces simultaneous data connectivity; Wi-Fi-to-mobile handover can be slower.","global","mobile_data_always_on",Arrays.asList("0","1"));
        for(String[] animation:new String[][]{{"window_animation","Window animations","window_animation_scale"},{"transition_animation","Transition animations","transition_animation_scale"},{"animator","Animator duration","animator_duration_scale"}})
            add(animation[0],animation[1],"Zero removes optional animation work; apps may ignore this scale.","global",animation[2],Arrays.asList("0","0.5","1"));
        add("night_mode","System dark mode","Always dark can reduce OLED display power in apps that follow the theme. App-specific themes and wallpaper are unchanged.","night","",Arrays.asList("0","1","2","3"));
    }
    String get(JSONObject d) throws Exception {
        String kind=d.getString("kind"), target=d.getString("target");
        if(kind.equals("power"))return context.getSystemService(android.os.PowerManager.class).isPowerSaveMode()?"1":"0";
        if(kind.equals("sync"))return android.content.ContentResolver.getMasterSyncAutomatically()?"1":"0";
        if(kind.equals("data_saver"))return (Boolean)network("getRestrictBackground",new Class<?>[]{})?"1":"0";
        if(kind.equals("night"))return ""+context.getSystemService(android.app.UiModeManager.class).getNightMode();
        if(kind.equals("policy"))return ACTIVE.exists()?new JSONObject(read(ACTIVE)).optJSONObject("controls").optString("background","unrestricted"):"unrestricted";
        if(kind.equals("net_uid"))return network("getUidPolicy",new Class<?>[]{int.class},appUid(d)).toString();
        if(kind.equals("bg_run") || kind.equals("bg_any")) {
            int op=d.getInt("op");
            List<?> packages=(List<?>)binderCall("appops","com.android.internal.app.IAppOpsService","getUidOps",new Class<?>[]{int.class,int[].class},appUid(d),new int[]{op});
            if(packages!=null)for(Object pkg:packages) {
                List<?> entries=(List<?>)pkg.getClass().getMethod("getOps").invoke(pkg);
                for(Object entry:entries)if((Integer)entry.getClass().getMethod("getOp").invoke(entry)==op)
                    return entry.getClass().getMethod("getMode").invoke(entry).toString();
            }
            return android.app.AppOpsManager.class.getMethod("opToDefaultMode",int.class).invoke(null,op).toString();
        }
        if(kind.equals("kernel"))return BatteryTransport.request("get",target,"").getString("value");
        if(kind.equals("device_config")) {
            String[] t=target.split("/");Object value=config("getProperty",new Class<?>[]{String.class,String.class},t[0],t[1]);return value==null?"null":value.toString();
        }
        if(kind.equals("bucket")) {
            android.app.usage.UsageStatsManager manager=context.getSystemService(android.app.usage.UsageStatsManager.class);
            return android.app.usage.UsageStatsManager.class.getMethod("getAppStandbyBucket",String.class).invoke(manager,target).toString();
        }
        if(kind.equals("exemption")) {
            String[] packages=(String[])idle("getUserPowerWhitelist",new Class<?>[]{});
            return Boolean.toString(Arrays.asList(packages).contains(target));
        }
        String value=kind.equals("global")?android.provider.Settings.Global.getString(context.getContentResolver(),target):android.provider.Settings.System.getString(context.getContentResolver(),target);
        return value==null?"null":value;
    }
    void set(JSONObject d,String value)throws Exception {
        String kind=d.getString("kind"),target=d.getString("target");
        if(value.equals(get(d)) && !(kind.equals("night") && value.equals("3") && d.has("nightCustomType")))return; // Saver can reject no-ops while charging.
        if(kind.equals("power")) {
            boolean ok=(Boolean)android.os.PowerManager.class.getMethod("setPowerSaveModeEnabled",boolean.class).invoke(context.getSystemService(android.os.PowerManager.class),value.equals("1"));
            if(!ok)throw new IOException("Battery Saver request rejected (possibly charging)");
        } else if(kind.equals("sync"))android.content.ContentResolver.setMasterSyncAutomatically(value.equals("1"));
        else if(kind.equals("data_saver"))network("setRestrictBackground",new Class<?>[]{boolean.class},value.equals("1"));
        else if(kind.equals("night")) {
            android.app.UiModeManager manager=context.getSystemService(android.app.UiModeManager.class);
            if(value.equals("3") && d.has("nightCustomType")) {
                android.app.UiModeManager.class.getMethod("setNightModeCustomType",int.class).invoke(manager,d.getInt("nightCustomType"));
                if((Integer)android.app.UiModeManager.class.getMethod("getNightModeCustomType").invoke(manager)!=d.getInt("nightCustomType"))throw new IOException("Dark-mode schedule type was not restored");
            }else manager.setNightMode(Integer.parseInt(value));
        }
        else if(kind.equals("net_uid"))network("setUidPolicy",new Class<?>[]{int.class,int.class},appUid(d),Integer.parseInt(value));
        else if(kind.equals("bg_run") || kind.equals("bg_any"))
            android.app.AppOpsManager.class.getMethod("setUidMode",int.class,int.class,int.class).invoke(context.getSystemService(android.app.AppOpsManager.class),d.getInt("op"),appUid(d),Integer.parseInt(value));
        else if(kind.equals("kernel"))BatteryTransport.request("set",target,value);
        else if(kind.equals("device_config")) {
            String[] t=target.split("/");
            boolean ok=value.equals("null")?(Boolean)config("deleteProperty",new Class<?>[]{String.class,String.class},t[0],t[1])
                :(Boolean)config("setProperty",new Class<?>[]{String.class,String.class,String.class,boolean.class},t[0],t[1],value,false);
            if(!ok)throw new IOException("Device configuration rejected the change");
        } else if(kind.equals("bucket")) {
            android.app.usage.UsageStatsManager.class.getMethod("setAppStandbyBucket",String.class,int.class).invoke(context.getSystemService(android.app.usage.UsageStatsManager.class),target,Integer.parseInt(value));
        } else if(kind.equals("exemption"))idle(value.equals("true")?"addPowerSaveWhitelistApp":"removePowerSaveWhitelistApp",new Class<?>[]{String.class},target);
        else {
            String actual=value.equals("null")?null:value;
            boolean ok=kind.equals("global")?android.provider.Settings.Global.putString(context.getContentResolver(),target,actual):android.provider.Settings.System.putString(context.getContentResolver(),target,actual);
            if(!ok)throw new IOException("Android rejected the setting");
        }
        String actual=get(d);boolean equal=value.equals(actual);
        if(!equal)try{equal=Double.parseDouble(value)==Double.parseDouble(actual);}catch(Exception ignored){}
        if(!equal)throw new IOException("Readback differs: requested "+value+", read "+actual);
    }
    void saveJournal() throws Exception { atomic(JOURNAL,journal.toString()); }
    void snapshot(String key, JSONObject descriptor) throws Exception {
        if(!journal.has(key)) {
            JSONObject entry=new JSONObject(descriptor.toString());
            entry.put("original",get(descriptor)).put("boot",bootId);
            if(descriptor.getString("kind").equals("night") && entry.getString("original").equals("3"))
                entry.put("nightCustomType",android.app.UiModeManager.class.getMethod("getNightModeCustomType").invoke(context.getSystemService(android.app.UiModeManager.class)));
            journal.put(key,entry); saveJournal(); // Durable undo before the first write.
        }
    }
    void change(String key, JSONObject descriptor, String value) throws Exception {
        snapshot(key,descriptor);
        set(descriptor,value);
        notes.add(descriptor.optString("label",key)+": applied "+value);
    }
    boolean restore(String key) throws Exception {
        JSONObject d=journal.getJSONObject(key);
        if(d.getString("kind").equals("kernel") && !bootId.equals(d.optString("boot"))) {
            journal.remove(key); saveJournal(); return true; // Kernel values do not survive reboot.
        }
        try { set(d,d.getString("original")); journal.remove(key); saveJournal(); notes.add(d.optString("label",key)+": restored"); return true; }
        catch(Exception e) { notes.add("Restore failed for "+key+": "+e.getMessage()); return false; }
    }
    boolean apply(JSONObject profile) throws Exception {
        boolean enabled=profile.getBoolean("enabled");
        JSONObject desired=new JSONObject(); JSONObject controls=profile.getJSONObject("controls");
        if(enabled) {
            for(Iterator<String> it=controls.keys();it.hasNext();) { String k=it.next(); desired.put(k,controls.getString(k)); }
            for(String field:new String[]{"packages","exemptions"}) {
                JSONArray a=profile.getJSONArray(field);
                for(int i=0;i<a.length();i++) desired.put((field.equals("packages")?"bucket:":"exemption:")+a.getString(i),field.equals("packages")?profile.getString("bucket"):"false");
            }
        }
        boolean success=true;
        Map<String,JSONObject> appDescriptors=new LinkedHashMap<>();
        if(enabled && "restricted".equals(controls.optString("background"))) {
            try {
                List<String> targets=backgroundTargets();
                notes.add("Aggressive background policy: "+targets.size()+" apps; system services, launcher, keyboards and dialer protected");
                for(String pkg:targets)for(String kind:new String[]{"bucket","exemption","bg_run","bg_any","net_uid"}) {
                    String key=kind+":"+pkg;
                    try {
                        JSONObject d=packageDescriptor(kind,pkg);appDescriptors.put(key,d);
                        String value=kind.equals("bucket")?"45":kind.equals("exemption")?"false":kind.equals("net_uid")?""+((Integer.parseInt(get(d))|1)&~4):"1";
                        desired.put(key,value);
                    }catch(Exception e){success=false;notes.add(key+": "+e.getMessage());}
                }
            }catch(Exception e){success=false;notes.add("Cannot enumerate background apps: "+e.getMessage());}
        }
        desired.remove("background"); // Expansion policy, not a system setting.
        List<String> originals=new ArrayList<>(); for(Iterator<String> it=journal.keys();it.hasNext();) originals.add(it.next());
        // Saver can change other policies as a side effect: restore it before their snapshots.
        originals.sort((a,b)->Integer.compare(a.equals("power_saver")?0:1,b.equals("power_saver")?0:1));
        for(String k:originals) {
            JSONObject e=journal.getJSONObject(k);
            if(e.getString("kind").equals("kernel") && !bootId.equals(e.optString("boot"))) { journal.remove(k); saveJournal(); }
            else if (k.equals("refresh_min") && desired.has("refresh")
                    && !e.getString("original").equals("null")
                    && Double.parseDouble(e.getString("original")) > Double.parseDouble(desired.getString("refresh"))) {
                // Retain the original minimum while a lower maximum remains selected.
            } else if(!desired.has(k)) success=restore(k)&&success;
        }
        Map<String,JSONObject> prepared=new LinkedHashMap<>();
        // Snapshot ALL selected controls before any write: Battery Saver has cross-control effects.
        for(Iterator<String> it=desired.keys();it.hasNext();) {
            String key=it.next(),value=desired.getString(key);
            try {
                JSONObject d=appDescriptors.get(key);
                if(d==null && (key.startsWith("bucket:") || key.startsWith("exemption:")))
                    d=packageDescriptor(key.substring(0,key.indexOf(':')),key.substring(key.indexOf(':')+1));
                if(d==null) {
                    d=specs.get(key);
                    if(d==null || !d.optBoolean("supported"))throw new IOException("Unsupported on this device");
                    boolean valid=false;JSONArray values=d.getJSONArray("values");for(int i=0;i<values.length();i++)if(value.equals(values.getString(i)))valid=true;
                    if(!valid)throw new IOException("Value is outside this device's available choices");
                }
                if(key.equals("refresh")) {
                    JSONObject min=new JSONObject().put("id","refresh_min").put("kind","system").put("target","min_refresh_rate").put("label","Minimum refresh rate");
                    String before=get(min);
                    if(!before.equals("null") && Double.parseDouble(before)>Double.parseDouble(value)) {
                        snapshot("refresh_min",min);prepared.put("refresh_min",min);
                    }
                }
                snapshot(key,d);prepared.put(key,d);
            }catch(Exception e){success=false;notes.add(key+": "+e.getMessage());}
        }
        List<String> order=new ArrayList<>(prepared.keySet());
        order.sort((a,b)->Integer.compare(writeOrder(a),writeOrder(b)));
        for(String key:order)try {
            change(key,prepared.get(key),key.equals("refresh_min")?desired.getString("refresh"):desired.getString(key));
        }catch(Exception e){success=false;notes.add(key+": "+e.getMessage());}
        return success;
    }
    static int writeOrder(String key) {
        if(key.equals("power_saver"))return 0;
        if(key.equals("refresh_min"))return 1;
        if(key.equals("night_mode"))return 4; // May recreate Settings; all writes are already journaled.
        if(key.startsWith("cpu_") || key.equals("gpu"))return 3;
        return 2;
    }
    JSONObject run(String mode, JSONObject requested) throws Exception {
        bootId=read(new File("/proc/sys/kernel/random/boot_id"));
        journal=JOURNAL.exists()?new JSONObject(read(JOURNAL)):new JSONObject();
        discover();
        JSONObject profile=ACTIVE.exists()?Profile.validate(new JSONObject(read(ACTIVE))):Profile.empty();
        boolean ok=true;
        if(mode.equals("apply") || mode.equals("boot")) {
            if(mode.equals("apply")) {
                profile=Profile.validate(requested);
                atomic(ACTIVE,profile.toString());
            }
            ok=apply(profile);
            specs.clear(); discover();
        }
        JSONArray capabilities=new JSONArray(); for(JSONObject d:specs.values()) {
            JSONObject safe=new JSONObject(d.toString()); safe.remove("target"); safe.remove("kind");
            if(journal.has(d.getString("id"))) safe.put("original",journal.getJSONObject(d.getString("id")).getString("original"));
            capabilities.put(safe);
        }
        return new JSONObject().put("ok",ok).put("profile",profile).put("capabilities",capabilities).put("notes",array(notes))
                .put("pendingRestore",!profile.getBoolean("enabled") && journal.length()>0).put("kernel",System.getProperty("os.version", "Unknown"));
    }
    JSONObject execute(String mode,JSONObject requested)throws Exception {
        if(android.os.Process.myUid()!=1000)throw new IOException("Only owner Settings may apply battery profiles");
        synchronized(BatteryEngine.class) {
            if(!DIR.isDirectory()&&!DIR.mkdirs())throw new IOException("Cannot create battery profile storage");
            try(RandomAccessFile file=new RandomAccessFile(new File(DIR,"lock"),"rw");FileLock lock=file.getChannel().lock()) {
                if(mode.equals("boot")) {
                    JSONObject p=ACTIVE.exists()?Profile.validate(new JSONObject(read(ACTIVE))):Profile.empty();
                    JSONObject undo=JOURNAL.exists()?new JSONObject(read(JOURNAL)):new JSONObject();
                    if(!p.getBoolean("enabled")&&undo.length()==0)return new JSONObject().put("ok",true);
                }
                return run(mode,requested);
            }
        }
    }
}
