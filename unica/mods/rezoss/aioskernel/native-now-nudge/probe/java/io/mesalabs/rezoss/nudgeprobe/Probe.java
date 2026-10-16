package io.mesalabs.rezoss.nudgeprobe;
import android.app.Instrumentation;
import android.content.Context;
import android.os.Bundle;
import java.lang.reflect.*;
import java.util.*;

/** Local synthetic request using Samsung's installed SCS code and target identity. */
public class Probe extends Instrumentation {
    /** Root-assisted diagnostic only: run under the existing Samsung client UID. */
    public static void main(String[] argv)throws Exception {
        android.os.Looper.prepareMainLooper();
        Class<?> at=Class.forName("android.app.ActivityThread");Object thread=at.getMethod("systemMain").invoke(null);
        Context system=(Context)at.getMethod("getSystemContext").invoke(thread);
        final Context target=system.createPackageContext("com.samsung.android.smartsuggestions",Context.CONTEXT_INCLUDE_CODE|Context.CONTEXT_IGNORE_SECURITY);
        Probe probe=new Probe(){
            public Context getTargetContext(){return target;}
            public void finish(int code,Bundle result){System.out.println("PROBE_RESULT "+result);System.exit("PASS".equals(result.getString("status"))?0:1);}
        };
        probe.args=new Bundle();if(argv.length>0)probe.args.putString("mode",argv[0]);
        if(argv.length>1)probe.args.putString("prompt",argv[1]);
        new Thread(()->probe.onStart(),"SamsungClientProbe").start();android.os.Looper.loop();
    }
    public static void runInApp(final Context context,String mode,String prompt) {
        Probe probe=new Probe(){
            public Context getTargetContext(){return context;}
            public void finish(int code,Bundle result){
                String text="PROBE_RESULT "+result;android.util.Log.i("RezossNudgeClientProbe",text);
                try {java.nio.file.Files.write(new java.io.File(context.getCacheDir(),"rezoss-nudge-client-probe.txt").toPath(),text.getBytes("UTF-8"));}catch(Exception ignored){}
            }
        };
        probe.args=new Bundle();probe.args.putString("mode",mode);probe.args.putString("prompt",prompt);
        new Thread(()->probe.onStart(),"SamsungClientProbe").start();
    }
    Bundle args;
    public void onCreate(Bundle arguments){args=arguments;super.onCreate(arguments);start();}
    ClassLoader loader;
    Class<?> cls(String name)throws Exception{return loader.loadClass(name);}
    Object invoke(Object o,String name,Class<?>[] types,Object... values)throws Exception{return o.getClass().getMethod(name,types).invoke(o,values);}
    ClassLoader wireLoader;
    Class<?> wire(String n)throws Exception{return wireLoader.loadClass("com.samsung.android.aioskernelservice."+n);}
    Object wireRead(android.os.Parcel p,String name)throws Exception{return p.readTypedObject((android.os.Parcelable.Creator<?>)wire(name).getField("CREATOR").get(null));}
    void binderProbe(Bundle result)throws Exception {
        Context context=getTargetContext();android.content.pm.ApplicationInfo app=context.getPackageManager().getApplicationInfo("com.samsung.android.aioskernelservice",0);
        wireLoader=new dalvik.system.DexClassLoader(app.sourceDir,context.getCodeCacheDir().toString(),app.nativeLibraryDir,context.getClassLoader());
        java.util.concurrent.CountDownLatch connected=new java.util.concurrent.CountDownLatch(1),complete=new java.util.concurrent.CountDownLatch(1);
        android.os.IBinder[] service=new android.os.IBinder[1];String[] output=new String[1];int[] event=new int[1];
        android.content.ServiceConnection connection=new android.content.ServiceConnection(){
            public void onServiceConnected(android.content.ComponentName n,android.os.IBinder b){service[0]=b;connected.countDown();}
            public void onServiceDisconnected(android.content.ComponentName n){}
        };
        boolean bound=context.bindService(new android.content.Intent().setClassName("com.samsung.android.aioskernelservice","com.samsung.android.aioskernelservice.AIOSKernelService"),connection,Context.BIND_AUTO_CREATE);
        try {
            if(!bound||!connected.await(10,java.util.concurrent.TimeUnit.SECONDS))throw new IllegalStateException("AIOS bind failed");
            Object proxy=wire("IAIOSKernelService$Stub").getMethod("asInterface",android.os.IBinder.class).invoke(null,service[0]);
            Class<?> api=wire("IAIOSKernelService");
            Object init=api.getMethod("initSSNeuralCore").invoke(proxy);if(((Byte)init)!=0)throw new IllegalStateException("Init="+init);
            Object model=wire("ModelInfo").getConstructor(org.json.JSONObject.class).newInstance(new org.json.JSONObject("{\"replacement_profile\":\"rezoss-qwen3-v73\"}"));
            Object adapter=wire("AdapterInfo").getConstructor(String.class,String.class,String.class,String.class).newInstance("{}","{}","contextual_reply","en");
            Object loaded=api.getMethod("preLoadModel",wire("ModelInfo"),wire("AdapterInfo")).invoke(proxy,model,adapter);
            if(((Byte)loaded)!=0)throw new IllegalStateException("Preload="+loaded);
            int[] tokens=new int[1];Object counted=api.getMethod("getNumberofTokens",String.class,int[].class).invoke(proxy,"Hello world",tokens);
            if(((Byte)counted)!=0||tokens[0]<1)throw new IllegalStateException("Token count failed");result.putInt("tokens",tokens[0]);
            android.os.Binder callback=new android.os.Binder(){protected boolean onTransact(int code,android.os.Parcel data,android.os.Parcel reply,int flags){
                if(code==INTERFACE_TRANSACTION){reply.writeString("com.samsung.android.aioskernelservice.ITaskCallback");return true;}
                data.enforceInterface("com.samsung.android.aioskernelservice.ITaskCallback");
                try{event[0]=code;if(code==1||code==2){Object tensor=wireRead(data,"TaskIOTensor");List<?> prompts=(List<?>)tensor.getClass().getMethod("getMPrompts").invoke(tensor);
                    output[0]=(String)prompts.get(0).getClass().getMethod("getMPromptString").invoke(prompts.get(0));}
                    reply.writeNoException();reply.writeByte((byte)0);complete.countDown();
                }catch(Exception e){output[0]=e.toString();complete.countDown();reply.writeException(new IllegalArgumentException(e.toString()));}return true;
            }};
            Object cp=wire("ITaskCallback$Stub").getMethod("asInterface",android.os.IBinder.class).invoke(null,callback);
            Object prompt=wire("PromptTensor").getConstructor(String.class,String.class).newInstance("userSpeech",args.getString("prompt","My friend asks: Are you free for dinner tonight? Give three short reply suggestions on separate lines."));
            Object input=wire("TaskIOTensor").getConstructor(List.class,List.class,List.class,List.class).newInstance(Collections.singletonList("synthetic-test"),Collections.singletonList(prompt),Collections.emptyList(),Collections.emptyList());
            Class<?> priority=wire("TaskPriority");long start=android.os.SystemClock.elapsedRealtime();
            Object task=api.getMethod("inference",wire("TaskIOTensor"),priority,wire("ITaskCallback")).invoke(proxy,input,priority.getField("TASK_PRIO_NORMAL").get(null),cp);
            boolean cancel=Boolean.parseBoolean(String.valueOf(args.get("cancel")));if(cancel)wire("IAIOSTask").getMethod("cancel").invoke(task);
            if(!complete.await(60,java.util.concurrent.TimeUnit.SECONDS))throw new IllegalStateException("Callback timeout");
            result.putInt("callback",event[0]);result.putString("result",output[0]);result.putLong("elapsed_ms",android.os.SystemClock.elapsedRealtime()-start);
            if(event[0]!=(cancel?4:1))throw new IllegalStateException("Unexpected callback="+event[0]);
            Object unloaded=api.getMethod("unloadSSNeuralPipeline").invoke(proxy);if(((Byte)unloaded)!=0)throw new IllegalStateException("Unload="+unloaded);
            result.putString("status","PASS");
        }finally{if(bound)context.unbindService(connection);}
    }
    public void onStart(){
        Bundle result=new Bundle();Object replyer=null;
        try{
            if("binder".equals(args.getString("mode"))){binderProbe(result);finish(-1,result);return;}
            if("conversation-actions".equals(args.getString("mode"))||"handler".equals(args.getString("mode"))){
                android.view.textclassifier.TextClassificationManager manager=getTargetContext().getSystemService(android.view.textclassifier.TextClassificationManager.class);
                android.view.textclassifier.TextClassifier classifier=manager.getTextClassifier();
                Bundle extras=new Bundle();extras.putBoolean("now_nudge",true);extras.putString("PACKAGE_NAME","com.whatsapp");extras.putString("TITLE","Local synthetic test");
                android.view.textclassifier.ConversationActions.Message message=new android.view.textclassifier.ConversationActions.Message.Builder(android.view.textclassifier.ConversationActions.Message.PERSON_USER_OTHERS)
                    .setText(args.getString("prompt","Are you free for dinner tonight?")).setReferenceTime(java.time.ZonedDateTime.now()).build();
                android.view.textclassifier.ConversationActions.Request request=new android.view.textclassifier.ConversationActions.Request.Builder(Collections.singletonList(message))
                    .setHints(Collections.singletonList(android.view.textclassifier.ConversationActions.Request.HINT_FOR_NOTIFICATION)).setMaxSuggestions(3).setExtras(extras).build();
                long start=android.os.SystemClock.elapsedRealtime();
                android.view.textclassifier.ConversationActions actions;
                if("handler".equals(args.getString("mode"))){
                    loader=getTargetContext().getClassLoader();Class<?> handler=cls("com.samsung.android.smartsuggestions.service.smartreply.NowNudgeConversationActionsHandler");
                    Object h=handler.getConstructor(Context.class).newInstance(getTargetContext());
                    actions=(android.view.textclassifier.ConversationActions)handler.getMethod("suggestConversationActions",android.view.textclassifier.TextClassificationSessionId.class,android.view.textclassifier.ConversationActions.Request.class,android.os.CancellationSignal.class)
                        .invoke(h,null,request,new android.os.CancellationSignal());
                }else actions=classifier.suggestConversationActions(request);
                StringBuilder text=new StringBuilder();for(android.view.textclassifier.ConversationAction action:actions.getConversationActions())text.append(action.getType()).append(": ").append(action.getTextReply()).append("\n");
                result.putString("result",text.toString());result.putLong("elapsed_ms",android.os.SystemClock.elapsedRealtime()-start);result.putString("status",text.length()>0?"PASS":"EMPTY");
                finish(-1,result);return;
            }
            Context context=new android.content.ContextWrapper(getTargetContext().createPackageContext("com.samsung.android.smartsuggestions",Context.CONTEXT_INCLUDE_CODE|Context.CONTEXT_IGNORE_SECURITY)) { public Context getApplicationContext(){return this;} };loader=context.getClassLoader();
            Class<?> processor=cls("com.samsung.android.smartsuggestions.service.smartreply.prompt.sr.SrPromptProcessor");
            // Obtain AppInfo through the shipped implementation without copying its credentials.
            Class<?> unsafeType=Class.forName("sun.misc.Unsafe");Field f=unsafeType.getDeclaredField("theUnsafe");f.setAccessible(true);Object unsafe=f.get(null);
            Object p=unsafeType.getMethod("allocateInstance",Class.class).invoke(unsafe,processor);
            Field cf=processor.getDeclaredField("context");cf.setAccessible(true);cf.set(p,context);
            Object resolver=cls("com.samsung.android.app.deepsky.baseutil.secure.ApkSignatureResolverImpl").getConstructor(Context.class).newInstance(context);
            Field sf=processor.getDeclaredField("signatureResolver");sf.setAccessible(true);sf.set(p,resolver);
            Method infoMethod=processor.getDeclaredMethod("getAppInfo");infoMethod.setAccessible(true);Object info=infoMethod.invoke(p);
            Class<?> appInfo=cls("com.samsung.android.sdk.scs.ai.language.AppInfo"),category=cls("com.samsung.android.sdk.scs.ai.language.SmartReplyCategory");
            replyer=cls("com.samsung.android.sdk.scs.ai.language.SmartReplyer").getConstructor(Context.class).newInstance(context);
            Map<String,String> options=new HashMap<>();options.put("target_language",args.getString("language","en"));
            String prompt=args.getString("prompt","Others: Are you free for dinner tonight?\nUser:");
            long start=android.os.SystemClock.elapsedRealtime();
            Object task=invoke(replyer,"smartReply",new Class<?>[]{appInfo,String.class,category,Map.class},info,prompt,category.getField("CONTEXTUAL").get(null),options);
            Method complete=task.getClass().getMethod("isComplete");complete.setAccessible(true);
            while(!(Boolean)complete.invoke(task)&&android.os.SystemClock.elapsedRealtime()-start<90000)Thread.sleep(100);
            if(!(Boolean)complete.invoke(task))throw new IllegalStateException("SCS callback timeout");
            Method error=task.getClass().getMethod("getException");error.setAccessible(true);Object exception=error.invoke(task);if(exception!=null)throw new IllegalStateException("SCS failure",(Throwable)exception);
            Method get=task.getClass().getMethod("getResult");get.setAccessible(true);Object output=get.invoke(task);
            result.putString("result",String.valueOf(output));result.putLong("elapsed_ms",android.os.SystemClock.elapsedRealtime()-start);result.putString("status","PASS");
        }catch(Throwable e){java.io.StringWriter text=new java.io.StringWriter();e.printStackTrace(new java.io.PrintWriter(text));result.putString("status","FAIL");result.putString("error",text.toString());}
        finally{if(replyer!=null)try{invoke(replyer,"release",new Class<?>[0]);}catch(Exception ignored){}}
        finish("PASS".equals(result.getString("status"))?-1:0,result);
    }
}
