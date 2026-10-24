#!/usr/bin/env python3
# Copyright (c) 2026 UN1CA contributors
# SPDX-License-Identifier: GPL-3.0-or-later
"""Compile the actual bridge against small host models; no Android runtime proof.

Requires only Python 3 and a JDK. Sources and classes are retained in /tmp.
The fake controller deliberately models the upstream refreshPosted coalescing
contract, while the Android models provide a deterministic main message queue.
"""
from pathlib import Path
import subprocess
import tempfile

MOD = Path(__file__).resolve().parents[1]
WORK = Path(tempfile.mkdtemp(prefix="unica-island-host-tests."))
SOURCES = {
"android/os/Looper.java": """
package android.os;
public final class Looper {
 private static final Looper MAIN=new Looper();
 public static Looper getMainLooper(){return MAIN;}
 public static Looper myLooper(){return MAIN;}
}
""",
"android/os/Handler.java": """
package android.os;
import java.util.*;
public class Handler {
 private static class Entry { Handler owner; Runnable run; boolean delayed;
  Entry(Handler h,Runnable r,boolean d){owner=h;run=r;delayed=d;} }
 private static final List<Entry> queue=new ArrayList<>();
 private final Looper looper;
 public Handler(Looper l){looper=l;}
 public Looper getLooper(){return looper;}
 public boolean post(Runnable r){queue.add(new Entry(this,r,false));return true;}
 public boolean postDelayed(Runnable r,long delay){queue.add(new Entry(this,r,true));return true;}
 public void removeCallbacksAndMessages(Object token){queue.removeIf(e->e.owner==this);}
 public static void drain(){
  int limit=2000;
  while(true){
   Entry next=null;for(Entry e:queue)if(!e.delayed){next=e;break;}
   if(next==null)return;
   if(--limit==0)throw new AssertionError("Main queue did not settle");
   queue.remove(next);next.run.run();
  }
 }
}
""",
"android/database/ContentObserver.java": """
package android.database;
import android.os.Handler;
public abstract class ContentObserver {
 public final Handler handler;
 public ContentObserver(Handler h){handler=h;}
 public void onChange(boolean self){}
}
""",
"android/net/Uri.java": """
package android.net;
public class Uri {public final String key;public Uri(String k){key=k;}}
""",
"android/content/ContentResolver.java": """
package android.content;
import android.net.Uri;import android.database.ContentObserver;import java.util.*;
public class ContentResolver {
 private final Map<String,List<ContentObserver>> observers=new HashMap<>();
 public void registerContentObserver(Uri u,boolean child,ContentObserver o){
  observers.computeIfAbsent(u.key,k->new ArrayList<>()).add(o);
 }
 public void notifyKey(String key){
  for(ContentObserver o:observers.getOrDefault(key,Collections.emptyList()))
   o.handler.post(()->o.onChange(false));
 }
}
""",
"android/content/Context.java": """
package android.content;
public class Context {
 private final ContentResolver resolver=new ContentResolver();
 public Context getApplicationContext(){return this;}
 public ContentResolver getContentResolver(){return resolver;}
 public ClassLoader getClassLoader(){return getClass().getClassLoader();}
}
""",
"android/provider/Settings.java": """
package android.provider;
import android.content.ContentResolver;import android.net.Uri;import java.util.*;
public class Settings {
 public static class Global {
  public static final Map<String,Integer> values=new HashMap<>(), writes=new HashMap<>();
  public static Uri getUriFor(String k){return new Uri(k);}
  public static int getInt(ContentResolver r,String k,int d){return values.getOrDefault(k,d);}
  public static boolean putInt(ContentResolver r,String k,int v){
   values.put(k,v);writes.put(k,writes.getOrDefault(k,0)+1);r.notifyKey(k);return true;
  }
 }
}
""",
"android/content/res/Resources.java": """
package android.content.res;
import java.util.*;
public class Resources {
 private final Map<String,Integer> ids=new HashMap<>();
 public void add(String name,int id){ids.put(name,id);}
 public int getIdentifier(String n,String kind,String pkg){return ids.getOrDefault(n,0);}
 public String getResourceEntryName(int id){
  for(Map.Entry<String,Integer> e:ids.entrySet())if(e.getValue()==id)return e.getKey();
  throw new IllegalArgumentException("Unknown resource "+id);
 }
}
""",
"android/view/View.java": """
package android.view;
import android.content.Context;import android.content.res.Resources;import java.util.*;
public class View {
 public static final int VISIBLE=0,INVISIBLE=4,GONE=8;
 private final Context context;private final Resources resources;
 private int id,visibility;private final Map<Integer,View> children=new HashMap<>();
 public View(Context c,Resources r,int i){context=c;resources=r;id=i;}
 public int getId(){return id;}
 public Context getContext(){return context;}
 public Resources getResources(){return resources;}
 public boolean isAttachedToWindow(){return true;}
 public int getVisibility(){return visibility;}
 public void setVisibility(int v){visibility=v;}
 public View findViewById(int i){return children.get(i);}
 public void add(View v){children.put(v.id,v);}
 public Animator animate(){return new Animator();}
 public static class Animator {public void cancel(){}}
}
""",
"android/media/session/MediaSessionManager.java": """
package android.media.session;
public class MediaSessionManager {
 public interface OnActiveSessionsChangedListener {}
 public void removeOnActiveSessionsChangedListener(OnActiveSessionsChangedListener listener){}
}
""",
"android/util/Log.java": """
package android.util;
public class Log {public static String getStackTraceString(Throwable t){return t.toString();}
 public static int i(String t,String m){return 0;}
 public static int d(String t,String m){return 0;}
 public static int e(String t,String m,Throwable error){return 0;}
 public static int w(String t,String m){return 0;}
}
""",
"com/android/systemui/plugins/statusbar/StatusBarStateController.java": """
package com.android.systemui.plugins.statusbar;
public interface StatusBarStateController {interface StateListener {void onStateChanged(int state);}}
""",
"com/android/systemui/shade/ShadeExpansionListener.java": """
package com.android.systemui.shade;
public interface ShadeExpansionListener {void onPanelExpansionChanged(Object event);}
""",
"com/android/systemui/statusbar/window/StatusBarWindowStateListener.java": """
package com.android.systemui.statusbar.window;
public interface StatusBarWindowStateListener {void onStatusBarWindowStateChanged(int state);}
""",
"dev/android/dynamicisland/island/IslandController.java": """
package dev.android.dynamicisland.island;
import android.content.Context;import android.os.*;import android.view.View;
import android.media.session.MediaSessionManager;import java.util.*;
public class IslandController {
 public static IslandController instance;public static boolean failAttach;
 public final Handler main=new Handler(Looper.getMainLooper());
 public boolean refreshPosted,revealPending,deferRefresh;
 public int oaBindings,refreshes,shadeUpdates;public View view;
 public final Placement placement=new Placement(); public final Sources sources=new Sources(); public final Audio audio=new Audio();
 public int snapshots; public boolean peek,hideOriginal; public void onNotificationsSnapshot(java.util.List<?> all){snapshots++;}
 public boolean peekHeadsUp(Object sbn){return peek;} public boolean hidesOriginal(String key){return hideOriginal;}
 public static IslandController init(Context c,ClassLoader cl){
  if(instance==null)instance=new IslandController();return instance;
 }
 public void setOngoingActivityController(Object oa){oaBindings++;requestRefresh();}
 public void setCallController(Object call){}
 public void setCastController(Object cast){}
 public void setCastBundle(Object bundle){requestRefresh();}
 public void setBarState(int state){}
 public void setDozing(boolean dozing){}
 public void setStatusBarWindowState(int state){}
 public void setShadeExpansion(float fraction){shadeUpdates++;}
 public void onStatusBarViewAttached(View v){view=v;if(placement.window==null)placement.window=new Window();}
 public void onStatusBarViewDetached(View v){view=null;}
 public void requestRefresh(){
  if(!NativeBridge.shouldRun()||refreshPosted)return;
  refreshPosted=true;
  Runnable refresh=()->{refreshPosted=false;if(NativeBridge.shouldRun())refreshes++;};
  if(deferRefresh)main.postDelayed(refresh,100);else main.post(refresh);
 }
 public void onExitTargetsRequested(){}
 public static class Window {
  public boolean attached=!failAttach;
  public boolean isAdded(){return attached;}
  public void detach(){attached=false;}
 }
 public static class Placement {public Window window; public boolean revealPending; public void nativeStop(){if(window!=null)window.detach();window=null;}}
 public static class Sources {public final Media media=new Media();}
 public static class Audio {public void setActive(boolean value){}}
 public static class Media {
  public Object msm;public Object sessionsListener;public Map<Object,Object> tracked=new HashMap<>();
  public void start(){msm=new MediaSessionManager();}
  public void nativeResume(){start();} public void nativePause(){msm=null;tracked.clear();}
 }
}
""",
"dev/android/dynamicisland/island/BridgeTest.java": """
package dev.android.dynamicisland.island;
import android.content.*;import android.content.res.Resources;import android.os.Handler;
import android.provider.Settings;import android.view.View;import java.util.*;
import com.android.systemui.plugins.statusbar.StatusBarStateController.StateListener;
import com.android.systemui.shade.ShadeExpansionListener;
public class BridgeTest {
 static final String KEY="unica_dynamic_island";
 static final Context context=new Context();static final Resources resources=new Resources();
 static final View bar=new View(context,resources,10),chip=new View(context,resources,1);
 static final ShadeExpansionStateManager shade=new ShadeExpansionStateManager();
 static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
 static void set(boolean enabled){Settings.Global.putInt(context.getContentResolver(),KEY,enabled?1:0);}
 static void setup(){
  resources.add("ongoing_activity_capsule",1);bar.add(chip);
  NativeBridge.capture(new OngoingActivityController());
  NativeBridge.capture(new StatusBarStateControllerImpl());
  NativeBridge.capture(shade);NativeBridge.capture(new StatusBarWindowStateController());
  NativeBridge.attached(new ViewController());Handler.drain();
  check(!NativeBridge.shouldReplace(),"OFF must preserve stock behavior");
 }
 static void enable(){set(true);Handler.drain();check(NativeBridge.shouldReplace(),"Enable must become ready");}
 static void failure(){
  IslandController.failAttach=true;setup();set(true);
  check(!NativeBridge.shouldReplace(),"Cannot suppress stock before readiness");
  Handler.drain();check(!NativeBridge.shouldReplace(),"Attach failure must preserve stock behavior");
  check(chip.getVisibility()==View.VISIBLE,"Attach failure hid stock chip");
  check(Settings.Global.getInt(context.getContentResolver(),KEY,-1)==0,"Attach failure must roll back switch");
 }
 static void offOn(){
  setup();enable();IslandController c=IslandController.instance;
  check(chip.getVisibility()==View.GONE,"Ready island should suppress stock chip");
  c.deferRefresh=true;c.placement.revealPending=true;c.requestRefresh();
  check(c.refreshPosted,"Test must have a pending coalesced refresh");
  set(false);Handler.drain();
  check(!NativeBridge.shouldReplace(),"OFF must stop replacement");
  check(!c.refreshPosted&&!c.placement.revealPending,"OFF must reset cancelled refresh/reveal flags");
  check(chip.getVisibility()==View.VISIBLE,"OFF must restore prior stock visibility");
  int previous=c.refreshes;c.deferRefresh=false;enable();
  check(c.refreshes>previous,"ON after OFF must resume refresh delivery");
  NativeBridge.refresh();Handler.drain();
  check(c.refreshes>previous+1,"Subsequent data events must still refresh");
 }
 static void shadeOnly(){
  setup();enable();IslandController c=IslandController.instance;
  int binds=c.oaBindings,writes=Settings.Global.writes.getOrDefault(KEY+"_state",0);
  int refreshes=c.refreshes,shadeUpdates=c.shadeUpdates;
  for(int i=0;i<20;i++){shade.fraction=i/20f;shade.emit();Handler.drain();}
  check(c.shadeUpdates>shadeUpdates,"Shade listener must actually deliver state");
  check(c.oaBindings==binds,"Shade events repeatedly rebound OA controller");
  check(c.refreshes==refreshes,"Shade-only events must not collect data again");
  check(Settings.Global.writes.getOrDefault(KEY+"_state",0)==writes,"Shade events rewrote loaded state");
 }
 static void notifications(){
  setup();enable();IslandController c=IslandController.instance;Builder builder=new Builder();
  Entry entry=new Entry();java.util.List<Entry> entries=java.util.Arrays.asList(entry);
  NativeBridge.captureListBuilder(builder);
  NativeBridge.snapshot(entries,null,new java.util.ArrayList<>());
  check(c.snapshots==0,"Only the pre-group pass may deliver snapshots");
  NativeBridge.snapshot(entries,null,builder.mNotifPreGroupFilters);
  check(c.snapshots==1,"Snapshot did not reach the new source controller");
  check(!NativeBridge.headsUp(builder,entry),"An unclaimed banner must stay stock");
  c.peek=true;check(NativeBridge.headsUp(builder,entry),"Ready claimed banner must reach island");
  check(NativeBridge.pinStatus(builder,new Head(entry),Pin.PinnedBySystem)==Pin.NotPinned,"System pin should be replaced");
  check(NativeBridge.pinStatus(builder,new Head(entry),Pin.PinnedByUser)==Pin.PinnedByUser,"User pin must be preserved");
  c.hideOriginal=true;check(NativeBridge.hideNotification(entry),"Requested progress row filter missing");
  set(false);Handler.drain();check(!NativeBridge.hideNotification(entry),"OFF must restore stock filtering");
  check(!NativeBridge.headsUp(builder,entry),"OFF must keep stock heads-up");
 }
 public static class Entry {public Object mSbn=new android.service.notification.StatusBarNotification();}
 public static class Head {public Object mEntry;Head(Object e){mEntry=e;}}
 public enum Pin {NotPinned,PinnedBySystem,PinnedByUser}
 public static class Builder {public java.util.List<Object> mNotifPreGroupFilters=java.util.Arrays.asList(new Object());}
 public static void main(String[] args){
  if(args[0].equals("notifications"))notifications();
  else if(args[0].equals("failure"))failure();
  else if(args[0].equals("off-on"))offOn();else shadeOnly();
  System.out.println("PASS "+args[0]);
 }
 public static class ViewController {public View mView=bar;}
 public static class OngoingActivityController {
  public View mParentView=chip;
  public void updateParentViewVisibility(boolean animate){}
  public void sendChipInfoForAppExitAnimation(){}
 }
 public static class StatusBarStateControllerImpl {
  public int mState=0;public boolean mIsDozing=false;
  public void addCallback(StateListener listener){}
 }
 public static class ShadeExpansionStateManager {
  public float fraction;private ShadeExpansionListener listener;
  public void addExpansionListener(ShadeExpansionListener l){listener=l;}
  public void emit(){listener.onPanelExpansionChanged(null);}
 }
 public static class StatusBarWindowStateController {
  public int displayId=0,windowState=0;public Set<Object> listeners=new HashSet<>();
 }
}
""",
}

SOURCES["dev/android/dynamicisland/settings/GlobalPrefs.java"]="""
package dev.android.dynamicisland.settings;
public final class GlobalPrefs {public static void bind(android.content.Context c){}}
"""
SOURCES["dev/android/dynamicisland/xposed/Diagnostics.java"]="""
package dev.android.dynamicisland.xposed;
public final class Diagnostics {public static void event(String s){} public static void error(String s,Throwable t){}}
"""
SOURCES["android/service/notification/StatusBarNotification.java"]="""
package android.service.notification;
public class StatusBarNotification {public String getKey(){return "test";}}
"""
for name, source in SOURCES.items():
    path = WORK / "src" / name
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(source)
actual = [MOD / "java/dev/android/dynamicisland" / part for part in (
    "island/NativeBridge.java", "xposed/Refl.java", "xposed/XLog.java", "xposed/hooks/HeadsUpHooks.java")]
classes = WORK / "classes"
classes.mkdir()
print(f"Host test sources and classes: {WORK}", flush=True)
subprocess.run(["javac", "-d", str(classes), *map(str, actual),
                *map(str, sorted((WORK / "src").rglob("*.java")))], check=True)
for scenario in ("failure", "off-on", "shade-only", "notifications"):
    subprocess.run(["java", "-cp", str(classes),
                    "dev.android.dynamicisland.island.BridgeTest", scenario], check=True)
print("Host models passed; Android rendering, actual SystemUI lifecycle and device behavior remain unverified.")
