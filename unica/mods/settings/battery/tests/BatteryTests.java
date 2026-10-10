/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.mesalabs.unica.battery;
import org.json.*;
import java.util.*;

/** Runs under Android's real JSON implementation; power IO is simulated. */
public final class BatteryTests {
    static int count;
    static void check(boolean value,String name){if(!value)throw new AssertionError(name);count++;}
    interface Attempt {void run()throws Exception;}
    static void rejects(Attempt a,String name)throws Exception{try{a.run();throw new AssertionError(name);}catch(JSONException expected){count++;}}
    static JSONObject descriptor(String id,String current,String... values)throws Exception{return new JSONObject().put("id",id).put("label",id).put("kind","kernel").put("target",id).put("current",current).put("supported",true).put("values",new JSONArray(Arrays.asList(values)));}
    static class Fake extends BatteryEngine {
        final Map<String,String> live=new HashMap<>();boolean failWrite,failRestore;int saves;
        Fake()throws Exception{super(null);journal=new JSONObject();bootId="one";JSONObject d=descriptor("swappiness","130","60","130");specs.put("swappiness",d);live.put("swappiness","130");}
        @Override String get(JSONObject d)throws Exception{return live.get(d.getString("target"));}
        @Override void set(JSONObject d,String value)throws Exception{
            if(!journal.has(d.getString("id")))throw new AssertionError("Write without journal");
            if(failWrite || (failRestore&&value.equals("130")))throw new java.io.IOException("simulated write failure");
            live.put(d.getString("target"),value);
        }
        @Override void saveJournal(){saves++;}
    }
    static class PolicyFake extends BatteryEngine {
        final Map<String,String> live=new HashMap<>();final List<String> writes=new ArrayList<>();
        boolean failAppRestore;
        PolicyFake()throws Exception{
            super(null);journal=new JSONObject();bootId="one";
            for(String id:new String[]{"power_saver","sync","data_saver"}) {
                JSONObject d=descriptor(id,id.equals("sync")?"1":"0","0","1").put("kind","global");
                specs.put(id,d);live.put(id,d.getString("current"));
            }
            live.put("bucket:com.test.app","20");live.put("exemption:com.test.app","true");
            live.put("bg_run:com.test.app","0");live.put("bg_any:com.test.app","3");live.put("net_uid:com.test.app","12");
        }
        @Override List<String> backgroundTargets(){return Arrays.asList("com.test.app");}
        @Override JSONObject packageDescriptor(String kind,String pkg)throws Exception{return new JSONObject().put("id",kind+":"+pkg).put("kind",kind).put("target",pkg);}
        @Override String get(JSONObject d)throws Exception{return live.get(d.getString("id"));}
        @Override void set(JSONObject d,String value)throws Exception{
            String id=d.getString("id");
            if(!journal.has(id))throw new AssertionError("Policy write without snapshot");
            if(failAppRestore&&id.equals("bg_any:com.test.app")&&value.equals("3"))throw new java.io.IOException("restore denied");
            if(id.equals("power_saver")&&value.equals("1")) {
                if(!journal.has("data_saver"))throw new AssertionError("Saver side effect before data snapshot");
                live.put("data_saver","1");
            }
            live.put(id,value);writes.add(id);
        }
        @Override void saveJournal(){}
    }
    static void stingyTests()throws Exception {
        JSONArray caps=new JSONArray()
            .put(descriptor("cpu_policy0","2016000","2016000","307200","556800").put("minimum","556800"))
            .put(descriptor("cpu_policy7","2841600","595200","864000","2841600").put("minimum","864000"))
            .put(descriptor("gpu","719000000","719000000","124800000").put("minimum","124800000"))
            .put(descriptor("refresh","120","120","60","24"))
            .put(descriptor("aspm","default","performance","powersave","powersupersave"))
            .put(descriptor("wifi_pm","on","auto","on"))
            .put(descriptor("wifi_wakeup","enabled","enabled","disabled"))
            .put(descriptor("freezer","device_default","enabled","disabled","device_default"))
            .put(descriptor("background","unrestricted","unrestricted","restricted"))
            .put(descriptor("swappiness","130","0","60","130"))
            .put(descriptor("cached","1024","32","1024"));
        for(String id:new String[]{"power_saver","sync","data_saver","brightness_mode","aod","wifi_scan","ble_scan","mobile_always"})caps.put(descriptor(id,"1","0","1"));
        for(String id:new String[]{"window_animation","transition_animation","animator"})caps.put(descriptor(id,"1","0","0.5","1"));
        caps.put(descriptor("brightness","128","1","8","26","128"));caps.put(descriptor("timeout","60000","15000","60000"));caps.put(descriptor("night_mode","1","0","1","2","3"));
        JSONObject stingy=Profile.validate(Profile.preset("Stingy",caps)), c=stingy.getJSONObject("controls");
        check(!stingy.getBoolean("enabled"),"Stingy selection stays disabled");
        caps.getJSONObject(0).put("adjustableMinimum",true);
        check(Profile.preset("Stingy",caps).getJSONObject("controls").getString("cpu_policy0").equals("307200"),"new worker selects hardware minimum below active floor");
        Fake clock=new Fake(){
            @Override void set(JSONObject d,String v)throws Exception {
                String id=d.getString("target");
                if(id.equals("cpu_policy0") && Long.parseLong(v)<Long.parseLong(live.get("min_cpu_policy0")))throw new java.io.IOException("ceiling below floor");
                if(id.equals("min_cpu_policy0") && Long.parseLong(v)>Long.parseLong(live.get("cpu_policy0")))throw new java.io.IOException("floor above ceiling");
                super.set(d,v);
            }
        };
        clock.specs.put("cpu_policy0",caps.getJSONObject(0));
        clock.live.put("cpu_policy0","2016000");clock.live.put("min_cpu_policy0","556800");
        JSONObject low=Profile.empty().put("enabled",true);
        low.getJSONObject("controls").put("cpu_policy0","307200");
        check(clock.apply(low),"lower floor before ceiling");
        check(clock.live.get("min_cpu_policy0").equals("307200"),"floor lowered");
        check(clock.apply(low),"repeat minimum profile");
        check(clock.journal.getJSONObject("min_cpu_policy0").getString("original").equals("556800"),"original floor retained");
        clock.failWrite=true;
        check(!clock.apply(Profile.empty()),"failed restore reported");
        check(clock.journal.has("min_cpu_policy0") && clock.journal.has("cpu_policy0"),"failed frequency restoration remains recoverable");
        clock.failWrite=false;
        check(clock.apply(Profile.empty()),"restore ceiling before floor");
        check(clock.live.get("cpu_policy0").equals("2016000") && clock.live.get("min_cpu_policy0").equals("556800"),"both limits restored");
        check(clock.journal.length()==0,"frequency journal cleared after restore");

        check(c.getString("cpu_policy0").equals("556800"),"CPU respects live floor and unsorted table");
        check(c.getString("cpu_policy7").equals("864000"),"prime cluster floor");
        check(c.getString("gpu").equals("124800000"),"minimum GPU");
        check(c.getString("refresh").equals("24"),"minimum advertised refresh");
        check(c.getString("aspm").equals("powersupersave"),"strongest advertised ASPM");
        check(c.getString("wifi_pm").equals("auto"),"runtime suspend allowed");
        check(c.getString("wifi_wakeup").equals("disabled"),"wifi wake disabled");
        check(c.getString("freezer").equals("enabled"),"freezer enabled");
        check(c.getString("background").equals("restricted"),"automatic background restrictions");
        for(String id:new String[]{"sync","brightness_mode","aod","wifi_scan","ble_scan","mobile_always","window_animation","transition_animation","animator"})check(c.getString(id).equals("0"),id+" disabled");
        check(c.getString("power_saver").equals("1")&&c.getString("data_saver").equals("1"),"savers on");
        check(c.getString("brightness").equals("8")&&c.getString("timeout").equals("15000")&&c.getString("night_mode").equals("2"),"display choices");
        check(!c.has("cached")&&!c.has("swappiness"),"no speculative memory tuning");
        check(Profile.validate(new JSONObject(stingy.toString())).getString("preset").equals("Stingy"),"Stingy backup roundtrip");
        check(Profile.preset("Stingy",new JSONArray()).getJSONObject("controls").length()==0,"unsupported device untouched");
        JSONArray missingFloor=new JSONArray().put(descriptor("cpu_policy0","2016000","307200","2016000"));
        check(!Profile.preset("Stingy",missingFloor).getJSONObject("controls").has("cpu_policy0"),"no guessed CPU floor");
        check(Profile.preset("Stingy",new JSONArray().put(descriptor("aspm","default","default","powersave"))).getJSONObject("controls").getString("aspm").equals("powersave"),"ASPM fallback");
        rejects(()->{JSONObject x=Profile.empty();x.getJSONObject("controls").put("background","stop-everything");Profile.validate(x);},"invalid background policy");
        rejects(()->{JSONObject x=Profile.empty();x.getJSONObject("controls").put("brightness","0");Profile.validate(x);},"black screen rejected");
        rejects(()->{JSONObject x=Profile.empty();x.getJSONObject("controls").put("sync","false");Profile.validate(x);},"invalid boolean choice");
        check(!BatteryEngine.restrictionTarget(1000,Collections.emptySet()),"system UID protected");
        check(BatteryEngine.restrictionTarget(10001,Collections.emptySet()),"non-launchable system app included");
        check(BatteryEngine.restrictionTarget(10123,Collections.emptySet()),"Play services included");
        Set<Integer> recovery=new HashSet<>(Arrays.asList(10100));
        check(!BatteryEngine.restrictionTarget(10100,recovery),"all packages sharing recovery UID protected");
        check(!BatteryEngine.restrictionTarget(1000,recovery),"core UID protected even without package entry");
        check(BatteryEngine.restrictionTarget(10123,recovery),"other app UID eligible");
        check(BatteryEngine.restrictionTarget(10123,Collections.emptySet()),"downloaded app included");
        check(BatteryEngine.restrictionTarget(10124,Collections.emptySet()),"Play Store included");
        check(BatteryEngine.restrictionTarget(10125,Collections.emptySet()),"Galaxy Store included");
        check(BatteryEngine.restrictionTarget(10126,Collections.emptySet()),"launchable preinstalled app included");
        PolicyFake f=new PolicyFake();JSONObject p=Profile.empty().put("enabled",true);
        p.getJSONObject("controls").put("background","restricted").put("power_saver","1").put("sync","0").put("data_saver","1");
        Map<String,String> original=new HashMap<>(f.live);
        check(f.apply(p),"Stingy policy applies");check(f.live.get("bg_any:com.test.app").equals("1"),"background appop ignored");
        check(f.live.get("net_uid:com.test.app").equals("9"),"metered reject preserves unrelated flags and removes allow");
        check(f.live.get("bucket:com.test.app").equals("45")&&f.live.get("exemption:com.test.app").equals("false"),"restricted bucket and exemption removed");
        check(f.journal.getJSONObject("data_saver").getString("original").equals("0"),"snapshot precedes Saver side effect");
        check(!f.journal.has("background"),"synthetic policy has no fake setting undo");
        check(f.apply(p),"repeat apply keeps originals");
        f.bootId="two";f.writes.clear();check(f.apply(Profile.empty()),"policy restore after reboot");
        check(f.writes.get(0).equals("power_saver"),"restore Saver before dependent controls");
        check(f.live.equals(original),"all app modes and policy flags restored exactly");
        check(f.journal.length()==0,"policy journal cleared");
        f=new PolicyFake();f.apply(p);f.failAppRestore=true;
        check(!f.apply(Profile.empty()),"appop restore failure reported");check(f.journal.has("bg_any:com.test.app"),"appop retry record retained");
        f.failAppRestore=false;check(f.apply(Profile.empty()),"appop restoration retried");
        PolicyFake expanded=new PolicyFake(){
            @Override List<String> backgroundTargets(){return Arrays.asList("com.google.android.gms","com.android.vending","com.sec.android.app.samsungapps","com.vendor.headless");}
        };
        for(String pkg:expanded.backgroundTargets())for(String kind:new String[]{"bucket","exemption","bg_run","bg_any","net_uid"})
            expanded.live.put(kind+":"+pkg,kind.equals("bucket")?"20":kind.equals("exemption")?"true":"0");
        Map<String,String> expandedOriginal=new HashMap<>(expanded.live);
        check(expanded.apply(p),"expanded system restrictions apply");
        for(String pkg:expanded.backgroundTargets()) {
            check(expanded.live.get("bg_any:"+pkg).equals("1") && expanded.live.get("net_uid:"+pkg).equals("1"),pkg+" background execution and metered data restricted");
            check(expanded.live.get("bucket:"+pkg).equals("45") && expanded.live.get("exemption:"+pkg).equals("false"),pkg+" standby and Doze restrictions");
        }
        check(expanded.apply(Profile.empty()) && expanded.live.equals(expandedOriginal),"expanded system restrictions fully restored");
        check(android.content.pm.PackageManager.class.getMethod("getPermissionControllerPackageName")!=null,"permission controller discovery API");
        JSONObject custom=Profile.empty().put("enabled",true).put("preset","Custom")
                .put("restricted_packages",new JSONArray().put("com.google.android.gms").put("com.android.vending").put("com.sec.android.app.samsungapps"));
        check(Profile.validate(custom).getJSONArray("restricted_packages").length()==3,"custom list imports all three requested packages");
        check(expanded.apply(custom),"custom full restrictions without global expansion");
        check(!expanded.journal.has("bg_any:com.vendor.headless"),"unselected system app remains untouched in custom mode");
        for(String pkg:new String[]{"com.google.android.gms","com.android.vending","com.sec.android.app.samsungapps"})
            check(expanded.live.get("bg_any:"+pkg).equals("1") && expanded.live.get("net_uid:"+pkg).equals("1"),pkg+" custom restriction");
        check(expanded.apply(Profile.empty()) && expanded.live.equals(expandedOriginal),"custom restrictions restore");
        JSONObject legacy=Profile.empty();legacy.remove("restricted_packages");
        check(Profile.validate(legacy).getJSONArray("restricted_packages").length()==0,"old profile imports with empty full-restriction list");
        rejects(()->Profile.validate(Profile.empty().put("restricted_packages",new JSONArray().put("invalid;pkg"))),"invalid full-restriction package rejected");
        Fake wireless=new Fake();
        for(String id:new String[]{"wifi_enabled","wifi_calling_1"}) {
            wireless.specs.put(id,descriptor(id,"1","0","1").put("kind",id.equals("wifi_enabled")?"wifi":"wifi_calling"));wireless.live.put(id,"1");
        }
        JSONObject wirelessProfile=Profile.empty().put("enabled",true);
        wirelessProfile.getJSONObject("controls").put("wifi_enabled","0").put("wifi_calling_1","0");
        check(Profile.validate(wirelessProfile).getJSONObject("controls").length()==2,"wireless choices validate");
        check(wireless.apply(wirelessProfile),"wireless controls journal and apply");
        wireless.failWrite=true;
        check(!wireless.apply(Profile.empty()) && wireless.journal.has("wifi_enabled"),"failed Wi-Fi restore retains original");
        wireless.failWrite=false;
        check(wireless.apply(Profile.empty()) && wireless.live.get("wifi_enabled").equals("1") && wireless.live.get("wifi_calling_1").equals("1"),"wireless controls restored");
        check(android.telephony.ims.ImsMmTelManager.class.getMethod("setVoWiFiSettingEnabled",boolean.class)!=null,"Wi-Fi Calling setter available");
        rejects(()->{JSONObject invalid=Profile.empty();invalid.getJSONObject("controls").put("wifi_calling_1","2");Profile.validate(invalid);},"invalid Wi-Fi Calling state");
        // Resolve the exact hidden APIs on the device without changing live settings.
        check(Class.forName("com.android.internal.app.IAppOpsService").getMethod("getUidOps",int.class,int[].class)!=null,"UID appop snapshot API");
        check(android.app.AppOpsManager.class.getMethod("setUidMode",int.class,int.class,int.class)!=null,"UID appop setter API");
        check(Class.forName("android.net.NetworkPolicyManager").getMethod("setUidPolicy",int.class,int.class)!=null,"network UID API");
        check(android.app.UiModeManager.class.getMethod("getNightModeCustomType")!=null && android.app.UiModeManager.class.getMethod("setNightModeCustomType",int.class)!=null,"dark schedule restoration API");
        check(android.os.PowerManager.class.getMethod("setPowerSaveModeEnabled",boolean.class)!=null,"Saver API");
    }
    static class Buckets extends BatteryEngine {
        int current=20,result=45,writes;Exception failure;
        Buckets()throws Exception{super(null);journal=new JSONObject();bootId="one";}
        @Override String get(JSONObject d){return ""+current;}
        @Override void writeBucket(String pkg,int value)throws Exception {writes++;if(failure!=null)throw failure;current=result;}
        @Override JSONObject packageDescriptor(String kind,String pkg)throws Exception{return new JSONObject().put("kind",kind).put("target",pkg);}
        @Override void saveJournal(){}
    }
    static void bucketTests()throws Exception {
        JSONObject p=Profile.empty().put("enabled",true).put("packages",new JSONArray().put("com.test.app"));
        String key="bucket:com.test.app";
        Buckets f=new Buckets();f.current=5;
        check(f.apply(p)&&f.policyLimited,"exempt target reported as policy limit");
        check(f.writes==0 && f.journal.length()==0,"exempt bucket never written or newly journaled");
        f=new Buckets();check(f.apply(p)&&!f.policyLimited,"ordinary bucket restriction succeeds");
        check(f.current==45 && f.journal.getJSONObject(key).getString("original").equals("20"),"ordinary restriction saved original");
        f=new Buckets();f.result=20;
        check(f.apply(p)&&f.policyLimited,"clamped restriction classified separately");
        check(f.journal.has(key),"clamped restriction keeps undo");
        check(!f.notes.toString().contains("applied 45"),"clamped restriction never claimed applied");
        f=new Buckets();f.result=20;f.journal.put(key,new JSONObject().put("kind","bucket").put("target","com.test.app").put("original","50"));
        check(f.apply(Profile.empty())&&f.policyLimited&&f.deferredRestores==1,"Never to Working-set restoration deferred");
        check(f.journal.getJSONObject(key).getString("original").equals("50"),"deferred original preserved exactly");
        f.result=50;check(f.apply(Profile.empty())&&f.journal.length()==0&&!f.policyLimited&&f.deferredRestores==0,"later exact restoration clears undo");
        f=new Buckets();f.journal.put(key,new JSONObject().put("kind","bucket").put("target","com.test.app").put("original","5"));
        check(f.apply(Profile.empty())&&f.deferredRestores==1&&f.writes==0,"system-owned saved bucket never sent to setter");
        check(f.journal.has(key),"system-owned original retained");
        f=new Buckets();f.result=50;
        check(!f.apply(p)&&!f.policyLimited,"unexpected more restricted result remains failure");
        f=new Buckets();f.failure=new java.lang.reflect.InvocationTargetException(new SecurityException("test permission denied"));
        check(!f.apply(p),"permission rejection remains failure");
        check(f.notes.toString().contains("SecurityException: test permission denied"),"reflection cause exposed");
        check(BatteryEngine.describe(new java.lang.reflect.InvocationTargetException(new IllegalArgumentException())).equals("IllegalArgumentException"),"null cause message gets type fallback");
        check(BatteryEngine.describe(new java.lang.reflect.InvocationTargetException(new java.lang.reflect.InvocationTargetException(new java.io.IOException("nested")))).equals("IOException: nested"),"nested reflection exceptions unwrapped");
    }
    static JSONObject enabled(String value)throws Exception{JSONObject p=Profile.empty();p.put("enabled",true);p.getJSONObject("controls").put("swappiness",value);return p;}
    public static void main(String[] args)throws Exception{
        JSONObject p=Profile.empty();check(!Profile.validate(p).getBoolean("enabled"),"disabled by default");
        check(Profile.validate(new JSONObject(p.toString())).toString().equals(p.toString()),"backup round trip");
        rejects(()->Profile.validate(new JSONObject(p.toString()).put("version",2)),"unknown version");
        rejects(()->Profile.validate(new JSONObject(p.toString()).put("target","/sys/anything")),"injected target");
        rejects(()->{JSONObject x=Profile.empty();x.getJSONObject("controls").put("swappiness","201");Profile.validate(x);},"swappiness upper bound");
        rejects(()->{JSONObject x=Profile.empty();x.getJSONObject("controls").put("cached","0");Profile.validate(x);},"cache lower bound");
        rejects(()->{JSONObject x=Profile.empty();x.getJSONObject("controls").put("wifi_pm","on; reboot");Profile.validate(x);},"injected shell value");
        rejects(()->{JSONObject x=Profile.empty();x.put("packages",new JSONArray().put("com.test;reboot"));Profile.validate(x);},"injected package");
        rejects(()->{JSONObject x=Profile.empty();x.put("enabled","true");Profile.validate(x);},"strict boolean");
        JSONObject packages=Profile.empty().put("packages",new JSONArray().put("com.test.app").put("com.test.app"));
        check(Profile.validate(packages).getJSONArray("packages").length()==1,"deduplicate packages");
        JSONArray caps=new JSONArray().put(descriptor("cpu_policy0","2016000","307200","2016000")).put(descriptor("cpu_policy7","3360000","595200","2726400","2841600","2956800"));
        JSONObject efficiency=Profile.preset("Efficiency",caps);
        check(efficiency.getJSONObject("controls").getString("cpu_policy7").equals("2841600"),"efficiency advertised ceiling");
        check(!efficiency.getJSONObject("controls").has("refresh"),"refresh optional");
        check(efficiency.getJSONArray("packages").length()==0,"no default restrictions");
        check(Profile.preset("Efficiency",new JSONArray()).getJSONObject("controls").length()==0,"missing CPU safe");
        check(Profile.preset("Balanced",caps).getJSONObject("controls").length()==0,"balanced defaults");
        JSONObject noslen=Profile.validate(Profile.preset("NOSLEN",caps));
        check(noslen.getJSONObject("controls").getString("wifi_pm").equals("on"),"NOSLEN runtime PM");
        check(noslen.getJSONObject("controls").getString("swappiness").equals("60"),"NOSLEN swappiness");
        check(noslen.getJSONArray("packages").length()==3,"NOSLEN apps");
        Fake f=new Fake();check(f.apply(enabled("60")),"apply succeeds");check(f.live.get("swappiness").equals("60"),"value applied");
        check(f.journal.getJSONObject("swappiness").getString("original").equals("130"),"snapshot original");
        check(f.saves>0,"journal persisted before write");
        check(f.apply(enabled("130")),"change succeeds");check(f.journal.getJSONObject("swappiness").getString("original").equals("130"),"original not overwritten");
        check(f.apply(Profile.empty()),"disable restores");check(f.journal.length()==0,"journal cleared after restoration");check(f.live.get("swappiness").equals("130"),"baseline restored");
        f=new Fake();f.apply(enabled("60"));JSONObject balanced=Profile.empty().put("enabled",true);check(f.apply(balanced),"switch Balanced restores");check(f.live.get("swappiness").equals("130"),"Balanced restores baseline");
        f=new Fake();check(!f.apply(enabled("59")),"unsupported device value rejected");check(f.journal.length()==0,"unsupported value no mutation");
        f=new Fake();f.failWrite=true;check(!f.apply(enabled("60")),"failed write reported");check(f.journal.has("swappiness"),"failed write retains undo");
        f=new Fake();f.apply(enabled("60"));f.failRestore=true;check(!f.apply(Profile.empty()),"restore failure reported");check(f.journal.has("swappiness"),"failed restore retained");f.failRestore=false;check(f.apply(Profile.empty()),"restore retry");
        f=new Fake();f.apply(enabled("60"));f.bootId="two";f.live.put("swappiness","140");check(f.apply(Profile.empty()),"reboot disable");check(f.live.get("swappiness").equals("140"),"old kernel snapshot not forced after reboot");
        f=new Fake();f.specs.get("swappiness").put("kind","global");f.live.put("swappiness","null");f.apply(enabled("60"));f.bootId="two";
        check(f.apply(Profile.empty()),"persistent restore across reboot");check(f.live.get("swappiness").equals("null"),"absent setting restored as deletion");
        stingyTests();
        bucketTests();
        JournalTests.run(args);
        System.out.println("PASS "+count+" profile and rollback assertions");
    }
}
