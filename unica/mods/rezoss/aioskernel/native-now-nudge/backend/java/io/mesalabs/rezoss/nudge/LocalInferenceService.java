package io.mesalabs.rezoss.nudge;

import android.app.Service;
import android.content.Intent;
import android.os.*;
import android.util.Log;
import java.lang.Process;
import java.util.*;
import java.util.concurrent.*;

/** Signature-protected RezossAI IPC; uses the same V73 queue as Samsung requests. */
public final class LocalInferenceService extends Service {
    private final Handler main=new Handler(Looper.getMainLooper());
    private final Map<IBinder,Job> jobs=new HashMap<>();
    private final ScheduledExecutorService watchdog=Executors.newSingleThreadScheduledExecutor();
    private TextBackend backend;
    private Messenger endpoint;
    private final class Job implements Runnable,TextBackend.Control,IBinder.DeathRecipient {
        final Messenger callback;final int uid;final String input;final boolean summary;final long deadline;
        volatile String cancellation;volatile Process process;volatile Thread thread;
        Job(Message m){callback=m.replyTo;uid=m.sendingUid;Bundle b=m.getData();input=b.getString("input","");summary=b.getBoolean("summary");deadline=Math.min(b.getLong("deadline",0),SystemClock.elapsedRealtime()+45000);}
        public boolean cancelled(){return cancellation!=null;}
        public long deadline(){return deadline;}
        public void process(Process p){process=p;if(p!=null&&cancelled())p.destroyForcibly();}
        synchronized void cancel(String reason){if(cancellation!=null)return;cancellation=reason;if(process!=null)process.destroyForcibly();if(thread!=null)thread.interrupt();}
        public void binderDied(){main.post(()->cancel("client_died"));}
        public void run(){
            thread=Thread.currentThread();String value=null,status="unavailable";
            try{if(!cancelled()){value=backend.generateLocal(input,summary,this);status="generated";}}
            catch(Exception e){status=e instanceof IllegalArgumentException?e.getMessage():"inference_failed";}
            finally{
                thread=null;Thread.interrupted();
                if(cancellation!=null){value=null;status=cancellation;}
                final String result=value,reason=status;
                main.post(()->{jobs.remove(callback.getBinder());try{callback.getBinder().unlinkToDeath(this,0);}catch(NoSuchElementException ignored){}respond(callback,result,reason);});
            }
        }
    }
    @Override public void onCreate(){
        super.onCreate();TextBackend.create(this);backend=TextBackend.local(this);
        endpoint=new Messenger(new Handler(Looper.getMainLooper()){
            @Override public void handleMessage(Message message){
                if(message.replyTo==null)return;
                try{
                    boolean trusted=false;
                    for(String caller:new String[]{"io.mesalabs.unica.localsummary"}){
                        try{if(message.sendingUid==getPackageManager().getApplicationInfo(caller,0).uid&&ModelStore.trusted(LocalInferenceService.this,caller))trusted=true;}catch(android.content.pm.PackageManager.NameNotFoundException ignored){}
                    }
                    if(!trusted)throw new SecurityException("caller_denied");
                    IBinder key=message.replyTo.getBinder();
                    if(message.what==2){Job job=jobs.get(key);if(job!=null&&job.uid==message.sendingUid)job.cancel("client_cancelled");return;}
                    if(message.what!=1)return;
                    if(backend==null||!backend.filesPresent()){respond(message.replyTo,null,"model_missing");return;}
                    if(!jobs.isEmpty()){respond(message.replyTo,null,"busy");return;}
                    Job job=new Job(message);
                    if(job.input.isEmpty()||job.input.length()>12000||job.deadline<=SystemClock.elapsedRealtime()){respond(message.replyTo,null,"invalid_or_expired");return;}
                    String blocked=ThermalGuard.blocked(LocalInferenceService.this);
                    if(blocked!=null){respond(message.replyTo,null,blocked);return;}
                    key.linkToDeath(job,0);jobs.put(key,job);
                    try{backend.submit(job);}catch(RejectedExecutionException busy){jobs.remove(key);key.unlinkToDeath(job,0);respond(message.replyTo,null,"busy");}
                }catch(Exception e){respond(message.replyTo,null,"caller_or_request_denied");}
            }
        });
        watchdog.scheduleAtFixedRate(()->main.post(()->{for(Job job:jobs.values()){String blocked=ThermalGuard.blocked(this);if(blocked==null&&SystemClock.elapsedRealtime()>=job.deadline)blocked="deadline";if(blocked!=null)job.cancel(blocked);}}),500,500,TimeUnit.MILLISECONDS);
    }
    private static void respond(Messenger callback,String value,String status){
        Message response=Message.obtain(null,1);Bundle b=new Bundle();b.putString("content",value);b.putString("status",status);response.setData(b);
        try{callback.send(response);}catch(RemoteException ignored){}
        Log.i("RezossV73Local","request status="+status);
    }
    @Override public IBinder onBind(Intent intent){return endpoint.getBinder();}
    @Override public void onDestroy(){for(Job job:jobs.values())job.cancel("service_stopped");watchdog.shutdownNow();TextBackend.close(this);super.onDestroy();}
}
