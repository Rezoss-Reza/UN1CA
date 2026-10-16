package io.mesalabs.rezoss.v73test;

import android.app.Instrumentation;
import android.content.Context;
import android.os.*;
import io.mesalabs.unica.localsummary.QwenEngine;
import org.json.*;

/** Runs against the actual installed RezossAI class, not a copied client implementation. */
public final class V73MigrationTest extends Instrumentation {
    private Bundle args;
    private Context inApp;
    public static void runInApp(Context context,String mode,int budget){V73MigrationTest test=new V73MigrationTest();test.inApp=context;test.args=new Bundle();test.args.putString("mode",mode);test.args.putString("budget",Integer.toString(budget));new Thread(()->test.onStart(),"V73MigrationProof").start();}
    private void complete(int code,Bundle result){
        if(inApp==null){finish(code,result);return;}
        try{JSONObject json=new JSONObject();for(String key:result.keySet())json.put(key,result.get(key));String name=args.getString("mode").replaceAll("[^a-z_]","");java.nio.file.Files.write(new java.io.File(inApp.getCacheDir(),"v73-migration-"+name+".json").toPath(),json.toString().getBytes("UTF-8"));android.util.Log.i("V73MigrationProof",json.toString());}catch(Exception e){android.util.Log.e("V73MigrationProof","Cannot save test result",e);}
    }
    @Override public void onCreate(Bundle args){this.args=args;start();}
    @Override public void onStart(){
        Bundle result=new Bundle();long start=SystemClock.elapsedRealtime();
        try{
            Context target=inApp==null?getTargetContext():inApp;
            QwenEngine.initialize(target);
            MigratedQwenEngine.initialize(target);
            String mode=args.getString("mode","summary");
            boolean direct=mode.startsWith("direct_");if(direct)mode=mode.substring(7);
            final boolean directRoute=direct;
            if(mode.equals("cancel")){
                Thread caller=new Thread(()->generate(directRoute,"Alice: Dinner tomorrow at seven. Bob: I will bring dessert.",true,30000));
                caller.start();Thread.sleep(2000);caller.interrupt();caller.join(3000);
                if(caller.isAlive())throw new AssertionError("cancellation_did_not_return");
            }else{
                boolean summary=!mode.equals("replies");
                String input=summary?"Alice: Lunch tomorrow at noon. Bob: I will bring sandwiches.":new JSONArray().put(new JSONObject().put("speaker","sender").put("text","Can we meet tomorrow afternoon?")).toString();
                String output=generate(direct,input,summary,mode.equals("missing")?3000:Integer.parseInt(args.getString("budget","40000")));
                if(mode.equals("missing")){
                    if(output!=null)throw new AssertionError("missing_model_generated_content");
                    if(SystemClock.elapsedRealtime()-start>2800)throw new AssertionError("missing_model_timeout");
                }else{
                    if(output==null||output.trim().isEmpty())throw new AssertionError("no_generated_result");
                    if(!summary){JSONObject replies=new JSONObject(output);for(String key:new String[]{"negative","neutral","positive"})if(replies.getString(key).isEmpty())throw new AssertionError("missing_tone");}
                    result.putString("generated",output);
                }
            }
            result.putString("status","PASS");result.putLong("elapsed_ms",SystemClock.elapsedRealtime()-start);complete(-1,result);
        }catch(Throwable error){result.putString("status","FAIL");result.putString("error",error.toString());result.putLong("elapsed_ms",SystemClock.elapsedRealtime()-start);complete(0,result);}
    }
    private static String generate(boolean direct,String input,boolean summary,int budget){return direct?MigratedQwenEngine.generate(input,summary,budget,true):QwenEngine.generate(input,summary,budget,true);}
}
