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
    }
    String get(JSONObject d) throws Exception {
        String kind=d.getString("kind"), target=d.getString("target");
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
        if(kind.equals("kernel"))BatteryTransport.request("set",target,value);
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
    void change(String key, JSONObject descriptor, String value) throws Exception {
        if(!journal.has(key)) {
            JSONObject entry=new JSONObject(descriptor.toString());
            entry.put("original",get(descriptor)).put("boot",bootId);
            journal.put(key,entry); saveJournal(); // Durable undo before the first write.
        }
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
        List<String> originals=new ArrayList<>(); for(Iterator<String> it=journal.keys();it.hasNext();) originals.add(it.next());
        for(String k:originals) {
            JSONObject e=journal.getJSONObject(k);
            if(e.getString("kind").equals("kernel") && !bootId.equals(e.optString("boot"))) { journal.remove(k); saveJournal(); }
            else if (k.equals("refresh_min") && desired.has("refresh")
                    && !e.getString("original").equals("null")
                    && Double.parseDouble(e.getString("original")) > Double.parseDouble(desired.getString("refresh"))) {
                // Retain the original minimum while a lower maximum remains selected.
            } else if(!desired.has(k)) success=restore(k)&&success;
        }
        for(Iterator<String> it=desired.keys();it.hasNext();) {
            String key=it.next(), value=desired.getString(key);
            JSONObject d=specs.get(key);
            try {
                if(key.startsWith("bucket:") || key.startsWith("exemption:")) {
                    String pkg=key.substring(key.indexOf(':')+1);
                    if(pkg.equals("com.android.settings") || pkg.equals("com.android.systemui") || pkg.equals("com.google.android.gms")) throw new IOException("Essential service protected");
                    context.getPackageManager().getApplicationInfo(pkg,0);
                    d=new JSONObject().put("kind",key.startsWith("bucket:")?"bucket":"exemption").put("target",pkg).put("label",pkg);
                } else {
                    if(d==null || !d.optBoolean("supported")) throw new IOException("Unsupported on this device");
                    boolean valid=false; JSONArray values=d.getJSONArray("values"); for(int i=0;i<values.length();i++) if(value.equals(values.getString(i))) valid=true;
                    if(!valid) throw new IOException("Value is outside this device's available choices");
                }
                if(key.equals("refresh")) {
                    JSONObject min=new JSONObject().put("id","refresh_min").put("kind","system").put("target","min_refresh_rate").put("label","Minimum refresh rate");
                    String before=get(min); if(!before.equals("null") && Double.parseDouble(before)>Double.parseDouble(value)) change("refresh_min",min,value);
                }
                change(key,d,value);
            } catch(Exception e) { success=false; notes.add(key+": "+e.getMessage()); }
        }
        return success;
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
