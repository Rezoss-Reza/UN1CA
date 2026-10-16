package io.mesalabs.rezoss.ai;
import android.content.Context;
import android.os.Bundle;
import android.util.Log;
import android.view.textclassifier.ConversationAction;
import java.lang.reflect.*;
import java.util.*;
/** Transfers generated Samsung-parser results to the existing notification callback. */
public final class NativeReplyBridge {
 private static final Map<Object,List<String>> pending=Collections.synchronizedMap(new WeakHashMap<Object,List<String>>());
 private static Object get(Object object,String method)throws Exception{return object.getClass().getMethod(method).invoke(object);}
 private static boolean enabled(Context context)throws Exception{
  return (Boolean)Class.forName("io.mesalabs.rezoss.ai.NativeNowNudgeRoute").getMethod("enabled",Context.class).invoke(null,context);
 }
 public static boolean shouldGenerateNow(Context context,Object param){
  try{return enabled(context)&&(Boolean)get(param,"isOnSmartReply")&&!(Boolean)get(param,"isOnDisplayNudge")&&(Boolean)get(param,"hasCallback");}
  catch(Exception e){Log.e("RezossNudgeClient","Notification profile rejected",e);return false;}
 }
 private static String clean(String value){
  String text=value.trim().replaceFirst("^(?:[-*•]|[0-9]+[.)])\\s+", "").trim();
  if(text.startsWith("\""))text=text.substring(1).trim();
  if(text.endsWith("\""))text=text.substring(0,text.length()-1).trim();
  for(int i=0;i<3;i++)text=text.replaceFirst("(?i)^me\\s*:\\s*", "").replaceFirst("(?i)(?:^|\\s+)me\\s*:\\s*$", "").trim();
  return text;
 }
 public static void record(Context context,Object param,List<?> generated){
  if(!shouldGenerateNow(context,param))return;
  List<String> replies=new ArrayList<>();for(Object value:generated){if(value instanceof String){String text=clean((String)value);if(!text.isEmpty()&&!replies.contains(text))replies.add(text);}if(replies.size()==3)break;}
  if(!replies.isEmpty())pending.put(param,replies);
 }
 public static Object merge(Object param,Object original){
  List<String> replies=pending.remove(param);if(replies==null)return original;
  try{
   Object result=Class.forName("com.samsung.android.smartsuggestions.service.smartreply.base.SmartReplyResult")
    .getConstructor(List.class,List.class,List.class,String.class,Map.class)
    .newInstance(get(original,"getSuggestedItems"),replies,get(original,"getSuggestedActions"),get(original,"getErrorMessage"),get(original,"getExtras"));
   Log.i("RezossNudgeClient","Delivered generated notification replies="+replies.size());return result;
  }catch(Exception e){Log.e("RezossNudgeClient","Reply callback conversion failed",e);return original;}
 }
 public static void addReplyActions(Context context,List<ConversationAction> actions,Object result){
  try{if(!enabled(context))return;int count=0;
   for(Object value:(List<?>)get(result,"getSuggestedReplies")){
    if(!(value instanceof String))continue;
    String reply=clean((String)value);if(reply.isEmpty())continue;
    Bundle extras=new Bundle();extras.putBoolean("now_nudge",true);extras.putString("source","suggested_replies");
    actions.add(new ConversationAction.Builder(ConversationAction.TYPE_TEXT_REPLY).setTextReply(reply).setConfidenceScore(.5f).setExtras(extras).build());
    if(++count==3)break;
   }
  }catch(Exception e){Log.e("RezossNudgeClient","Reply action conversion failed",e);}
 }
}
