/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.mesalabs.unica.battery;

import android.app.job.*;
import android.content.*;
import org.json.JSONObject;
import java.io.File;

/** One scheduled restoration/reapply after boot; no resident service or polling loop. */
public final class BatteryBoot extends BroadcastReceiver {
    @Override public void onReceive(Context context,Intent intent) {
        if(!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction()) || android.os.Process.myUid()!=1000)return;
        BatteryEngine engine=new BatteryEngine(context);
        try {
            JSONObject profile=engine.ACTIVE.exists()?new JSONObject(BatteryEngine.read(engine.ACTIVE,BatteryEngine.PROFILE_LIMIT)):Profile.empty();
            JSONObject undo=BatteryEngine.readJournal(engine.JOURNAL);
            if(!profile.optBoolean("enabled")&&undo.length()==0)return;
            JobScheduler scheduler=context.getSystemService(JobScheduler.class);
            scheduler.schedule(new JobInfo.Builder(0x554e4241,new ComponentName(context,ApplyJob.class))
                    .setMinimumLatency(20000).setOverrideDeadline(60000).build());
        } catch(Exception e) {android.util.Log.e("UnicaBattery","Cannot schedule battery profile",e);}
    }
    public static final class ApplyJob extends JobService {
        @Override public boolean onStartJob(JobParameters parameters) {
            new Thread(()->{
                try {
                    BatteryEngine engine=new BatteryEngine(getApplicationContext());
                    JSONObject result=engine.execute("boot",null);
                    BatteryEngine.atomic(new File(engine.DIR,"boot-status.json"),result.toString());
                } catch(Exception e) {android.util.Log.e("UnicaBattery","Boot profile failed",e);}
                finally {jobFinished(parameters,false);}
            },"UnicaBatteryBoot").start();
            return true;
        }
        @Override public boolean onStopJob(JobParameters parameters){return false;}
    }
}
