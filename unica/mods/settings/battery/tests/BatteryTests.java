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
        System.out.println("PASS "+count+" profile and rollback assertions");
    }
}
