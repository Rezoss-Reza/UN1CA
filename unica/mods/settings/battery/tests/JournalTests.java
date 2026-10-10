/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.mesalabs.unica.battery;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import org.json.*;

/** Real file IO and simulated setting writes; never modifies device policy. */
public final class JournalTests {
    static void check(boolean ok,String message){BatteryTests.check(ok,message);}
    static JSONObject entry(String kind)throws Exception {
        return new JSONObject().put("kind",kind).put("target","com.test.app").put("original","0")
            .put("uid",10123).put("op",70).put("boot","boot-one").put("nightCustomType",1)
            .put("futureRestoreField","keep").put("label","UI label").put("help","UI help")
            .put("values",new JSONArray().put("0").put("1")).put("current","1");
    }
    static class Restore extends BatteryEngine {
        int writes,saves;
        Restore(JSONObject data)throws Exception {super(null);journal=data;bootId="boot-one";}
        @Override void set(JSONObject descriptor,String value)throws Exception {
            if(!value.equals(descriptor.getString("original")))throw new AssertionError("Wrong restoration value");
            writes++;
        }
        @Override void saveJournal(){saves++;}
    }
    static class RefuseSave extends BatteryTests.PolicyFake {
        RefuseSave()throws Exception{super();}
        @Override void saveJournal(){throw new IllegalStateException("simulated capacity/disk failure");}
    }
    public static void run(String[] args)throws Exception {
        File dir=Files.createTempDirectory(new File("/data/local/tmp").toPath(),"unica-journal-test-").toFile();
        File file=new File(dir,"original.json");
        JSONObject source=entry("bg_any"),compact=BatteryEngine.compactEntry(source);
        check(!compact.has("values")&&!compact.has("label")&&!compact.has("boot"),"strip UI and persistent boot metadata");
        for(String key:new String[]{"kind","target","original","uid","op","nightCustomType","futureRestoreField"})
            check(source.get(key).equals(compact.get(key)),"keep restore field "+key);
        check(BatteryEngine.compactEntry(entry("kernel")).getString("boot").equals("boot-one"),"keep kernel boot identity");
        JSONObject data=new JSONObject().put("app",source);
        BatteryEngine.writeJournal(file,data);
        check(BatteryEngine.readJournal(file).getJSONObject("app").getString("original").equals("0"),"journal disk roundtrip");
        byte[] before=Files.readAllBytes(file.toPath());
        try {BatteryEngine.writeJournal(file,data,8);throw new AssertionError("capacity accepted");}catch(IOException expected){}
        check(Arrays.equals(before,Files.readAllBytes(file.toPath())),"capacity failure preserves old journal");
        File bounded=new File(dir,"bounded.txt");Files.write(bounded.toPath(),"12345678".getBytes(StandardCharsets.UTF_8));
        check(BatteryEngine.read(bounded,8).equals("12345678"),"exact byte limit readable");
        try {BatteryEngine.read(bounded,7);throw new AssertionError("read cap accepted");}catch(IOException expected){check(expected.getMessage().contains("bounded.txt"),"size error identifies file");}
        RefuseSave failed=new RefuseSave();JSONObject profile=Profile.empty().put("enabled",true);
        profile.getJSONObject("controls").put("power_saver","1").put("data_saver","1").put("background","restricted");
        try {failed.apply(profile);throw new AssertionError("unsaved profile applied");}catch(IllegalStateException expected){}
        check(failed.writes.isEmpty(),"zero control writes when snapshot persistence fails");
        check(failed.journal.length()==0,"unsaved snapshots removed from memory");
        RefuseSave restoreFailure=new RefuseSave();
        restoreFailure.journal.put("sync",new JSONObject().put("id","sync").put("kind","global").put("target","sync").put("original","1"));
        try {restoreFailure.apply(Profile.empty());throw new AssertionError("restore checkpoint unexpectedly saved");}catch(IllegalStateException expected){}
        check(restoreFailure.journal.has("sync"),"failed restore checkpoint retains retry record");
        // Representative legacy journal without shipping anyone's package inventory in this repo.
        JSONObject legacy=new JSONObject();StringBuilder help=new StringBuilder();for(int i=0;i<160;i++)help.append('x');
        for(int i=0;i<6500;i++)legacy.put("bg_any:com.test.app"+i,entry("bg_any").put("target","com.test.app"+i).put("help",help.toString()));
        BatteryEngine.atomic(file,legacy.toString());
        check(file.length()>1048576,"legacy regression exceeds old 1 MiB cap");
        recover(file,legacy);
        if(args.length>0) {
            File supplied=new File(args[0]);
            // Copy the supplied journal into the test directory; never modify the input.
            Files.copy(supplied.toPath(),new File(dir,"supplied.json").toPath());
            File copy=new File(dir,"supplied.json");
            JSONObject suppliedData=new JSONObject(BatteryEngine.read(copy,BatteryEngine.JOURNAL_LIMIT));
            recover(copy,suppliedData);
            System.out.println("Supplied journal: "+supplied.length()+" bytes, "+suppliedData.length()+" entries; compact "+copy.length()+" bytes");
        }
        System.out.println("Journal test artifacts: "+dir);
    }
    static void recover(File file,JSONObject legacy)throws Exception {
        byte[] before=Files.readAllBytes(file.toPath());
        JSONObject loaded=BatteryEngine.readJournal(file);
        check(loaded.length()==legacy.length(),"all legacy records loaded");
        for(Iterator<String> keys=legacy.keys();keys.hasNext();) {
            String key=keys.next();JSONObject old=legacy.getJSONObject(key), now=loaded.getJSONObject(key);
            for(String field:new String[]{"kind","target","original","uid","op","nightCustomType"})
                if(old.has(field) && !old.get(field).equals(now.get(field)))throw new AssertionError("Restoration metadata changed");
        }
        check(true,"all supplied restoration fields preserved");
        BatteryEngine.writeJournal(file,loaded);
        check(Arrays.equals(before,Files.readAllBytes(new File(file.getPath()+".legacy.bak").toPath())),"legacy backup byte-identical");
        check(file.length()<before.length,"legacy journal compacted");
        check(BatteryEngine.readJournal(file).length()==legacy.length(),"compacted journal reloads");
        Restore engine=new Restore(BatteryEngine.readJournal(file));
        int expected=0;
        for(Iterator<String> keys=engine.journal.keys();keys.hasNext();) {
            JSONObject d=engine.journal.getJSONObject(keys.next());
            if(!d.getString("kind").equals("kernel") || d.optString("boot").equals(engine.bootId))expected++;
        }
        check(engine.apply(Profile.empty()),"disabled/missing profile restores large journal");
        check(engine.writes==expected && engine.journal.length()==0,"all eligible originals restored; stale kernel entries discarded");
        check(engine.saves==(legacy.length()+63)/64,"restoration checkpointed in bounded batches");
    }
}
