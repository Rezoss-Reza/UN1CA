#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Host contract tests; Android views, Binder and Settings are simulated, not device proof."""
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
STUBS = {
    "android/R.java": "package android; public final class R { public static class string { public static int ok=1,cancel=2; } }",
    "android/content/ContentResolver.java": "package android.content; public class ContentResolver { public int user; public ContentResolver(int u){user=u;} }",
    "android/content/Intent.java": "package android.content; public class Intent { public static final String ACTION_MAIN=\"MAIN\",CATEGORY_HOME=\"HOME\"; public Intent(String s){} public Intent addCategory(String s){return this;} }",
    "android/content/DialogInterface.java": "package android.content; public interface DialogInterface { interface OnClickListener { void onClick(DialogInterface d,int w); } }",
    "android/content/Context.java": '''package android.content;
        public class Context {
            public final ContentResolver resolver; public final android.content.pm.PackageManager pm;
            public Context(){this(0,new android.content.pm.PackageManager());}
            public Context(int user,android.content.pm.PackageManager p){resolver=new ContentResolver(user);pm=p;}
            public ContentResolver getContentResolver(){return resolver;}
            public android.content.pm.PackageManager getPackageManager(){return pm;}
            public String getPackageName(){return "com.samsung.android.sm_cn";}
            public Context createContextAsUser(android.os.UserHandle u,int flags){return new Context(u.id,pm);}
        }''',
    "android/content/pm/ActivityInfo.java": "package android.content.pm; public class ActivityInfo { public String packageName; }",
    "android/content/pm/ResolveInfo.java": "package android.content.pm; public class ResolveInfo { public ActivityInfo activityInfo=new ActivityInfo(); }",
    "android/content/pm/PackageManager.java": '''package android.content.pm;
        public class PackageManager {
            public static final int MATCH_DEFAULT_ONLY=1,SIGNATURE_MATCH=0;
            public String home="com.teslacoilsw.launcher"; public boolean signed=true,manager=true;
            public ResolveInfo resolveActivity(android.content.Intent i,int f){ResolveInfo r=new ResolveInfo();r.activityInfo.packageName=home;return r;}
            public String[] getPackagesForUid(int uid){return new String[]{manager?"com.samsung.android.sm_cn":"com.example.app"};}
            public int checkSignatures(String a,String b){return signed?0:-1;}
            public boolean isPackageSuspended(String p){return IPackageManager.suspended.contains(p);}
        }''',
    "android/content/pm/SuspendDialogInfo.java": "package android.content.pm; public class SuspendDialogInfo {}",
    "android/content/pm/IPackageManager.java": '''package android.content.pm;
        public class IPackageManager {
            public static final java.util.Set<String> suspended=new java.util.HashSet<>();
            public static boolean reject,failRestore,journalSeen;
            public String[] setPackagesSuspendedAsUser(String[] p,boolean value,android.os.PersistableBundle a,
                    android.os.PersistableBundle b,SuspendDialogInfo d,int flags,String caller,int callingUser,int user){
                journalSeen=android.provider.Settings.Secure.getIntForUser(null,"unica_maximum_essential_pending_"+p[0],0,user)==1;
                if((value&&reject)||(!value&&failRestore)) return p;
                for(String pkg:p){if(value)suspended.add(pkg);else suspended.remove(pkg);}
                return new String[0];
            }
        }''',
    "android/os/Process.java": "package android.os; public class Process { public static int myUid(){return 10000;} }",
    "android/os/UserHandle.java": "package android.os; public class UserHandle { public int id; public static UserHandle of(int id){UserHandle u=new UserHandle();u.id=id;return u;} }",
    "android/os/PersistableBundle.java": "package android.os; public class PersistableBundle {}",
    "android/provider/Settings.java": '''package android.provider;
        public class Settings {
            public static class Secure {
                public static final java.util.Map<String,Integer> values=new java.util.HashMap<>();
                public static String failKey;
                public static int getInt(android.content.ContentResolver c,String k,int d){return getIntForUser(c,k,d,c.user);}
                public static int getIntForUser(android.content.ContentResolver c,String k,int d,int u){return values.getOrDefault(u+":"+k,d);}
                public static boolean putInt(android.content.ContentResolver c,String k,int v){if(k.equals(failKey))return false;values.put(c.user+":"+k,v);return true;}
            }
            public static class Global { public static int lowPower; public static int getInt(android.content.ContentResolver c,String k,int d){return lowPower;} }
        }''',
    "android/util/Log.java": "package android.util; public class Log { public static int w(String t,String m,Throwable e){return 0;} public static int e(String t,String m,Throwable e){return 0;} }",
    "android/view/View.java": '''package android.view;
        public class View {
            public static final int VISIBLE=0,GONE=8; public final android.content.Context context;
            public int visibility; public boolean clickable; public OnClickListener listener;
            public interface OnClickListener { void onClick(View v); }
            public View(android.content.Context c){context=c;}
            public android.content.Context getContext(){return context;}
            public void setOnClickListener(OnClickListener l){listener=l;clickable=l!=null;}
            public void setVisibility(int v){visibility=v;} public void setClickable(boolean v){clickable=v;}
        }''',
    "android/widget/CompoundButton.java": '''package android.widget;
        public class CompoundButton extends android.view.View {
            public boolean checked,enabled;
            public CompoundButton(android.content.Context c){super(c);}
            public void setOnCheckedChangeListener(Object o){} public void setEnabled(boolean v){enabled=v;}
            public void setChecked(boolean v){checked=v;} public void setContentDescription(String s){}
            public void jumpDrawablesToCurrentState(){}
        }''',
    "android/widget/Toast.java": "package android.widget; public class Toast { public static final int LENGTH_LONG=1; public static int shown; public static Toast makeText(android.content.Context c,String s,int l){shown++;return new Toast();} public void show(){} }",
    "android/app/AlertDialog.java": '''package android.app;
        public class AlertDialog { public static class Builder {
            android.content.DialogInterface.OnClickListener confirm;
            public Builder(android.content.Context c){} public Builder setTitle(String s){return this;}
            public Builder setMessage(String s){return this;}
            public Builder setNegativeButton(int i,android.content.DialogInterface.OnClickListener l){return this;}
            public Builder setPositiveButton(int i,android.content.DialogInterface.OnClickListener l){confirm=l;return this;}
            public void show(){confirm.onClick(null,1);}
        } }''',
    "android/app/AppGlobals.java": "package android.app; public class AppGlobals { public static android.content.pm.IPackageManager getPackageManager(){return new android.content.pm.IPackageManager();} }",
    "com/samsung/android/sm/powermode/ebs/data/EbsAppData.java": "package com.samsung.android.sm.powermode.ebs.data; public class EbsAppData { public int a; public String b,c; public boolean d,e; }",
    "rl/m.java": "package rl; public class m { public static Object e(boolean b){return null;} }",
    "sl/a.java": "package sl; public class a { public a(android.content.Context c){} public void b(int u,String action,java.util.ArrayList<String> p){} }",
    "com/android/server/pm/PackageManagerService.java": "package com.android.server.pm; public class PackageManagerService { public android.content.Context mContext; }",
}

HARNESS = '''
import android.content.Context;
import android.content.pm.IPackageManager;
import android.provider.Settings;
import android.view.View;
import android.widget.CompoundButton;
import com.samsung.android.sm.powermode.ebs.data.EbsAppData;
import com.android.server.pm.PackageManagerService;
import io.mesalabs.unica.maximum.*;

public class HelperTest {
    static int assertions;
    static Context c=new Context();
    static void check(boolean ok,String message){assertions++;if(!ok)throw new AssertionError(message);}
    static int read(String key){return Settings.Secure.getInt(c.getContentResolver(),key,0);}
    static void write(String key,int n){Settings.Secure.putInt(c.getContentResolver(),key,n);}
    static EbsAppData app(String pkg,boolean ordinary){EbsAppData d=new EbsAppData();d.b=pkg;d.c=pkg;d.d=ordinary;d.e=true;return d;}
    static CompoundButton click(EbsAppData data){View row=new View(c);CompoundButton button=new CompoundButton(c);EssentialApps.bind(row,button,data);check(row.listener!=null,"optional row click");row.listener.onClick(row);return button;}
    public static void main(String[] args){
        String pkg="com.samsung.android.dialer",off=MaximumPolicy.OFF+pkg,pending=MaximumPolicy.PENDING+pkg;
        EbsAppData data=app(pkg,false);EssentialApps.adjust(c,data);
        check(!data.d&&data.e,"essential defaults ON");
        check(read(MaximumPolicy.KNOWN+pkg)==1,"original essential recorded");
        check(!click(data).checked&&!data.e&&read(off)==1,"inactive OFF persists");
        check(IPackageManager.suspended.isEmpty(),"inactive change does not suspend");
        EbsAppData alternative=app("com.google.android.dialer",true);EssentialApps.adjust(c,alternative);
        check(alternative.d,"nonessential alternative stays in All apps");
        data=app(pkg,true);EssentialApps.adjust(c,data);
        check(!data.d&&!data.e,"OFF essential stays in Essential section");
        Settings.Global.lowPower=1;write("sem_power_mode_ebs_option",1);
        EssentialApps.beforeSuspend(c,true,new String[]{pkg,"com.example.app"},0);
        check(read(pending)==1,"batch recovery journal");
        IPackageManager.suspended.add(pkg);
        check(click(data).checked&&read(off)==0,"active ON restores selection");
        check(!IPackageManager.suspended.contains(pkg)&&read(pending)==0,"active ON unsuspends and clears journal");
        check(!click(data).checked&&read(off)==1,"active OFF selection");
        check(IPackageManager.suspended.contains(pkg)&&IPackageManager.journalSeen,"journal precedes suspension");
        EssentialApps.onMode(c,1);check(IPackageManager.suspended.contains(pkg),"enter does not restore");
        IPackageManager.failRestore=true;EssentialApps.onMode(c,0);
        check(read(pending)==1,"failed exit retains recovery journal");
        IPackageManager.failRestore=false;EssentialApps.onMode(c,0);
        check(!IPackageManager.suspended.contains(pkg)&&read(pending)==0,"exit retries restoration of role holder");
        check(read(off)==1,"exit retains next-cycle selection");
        write(pending,1);IPackageManager.suspended.add(pkg);EssentialApps.recoverIfInactive(c);
        check(IPackageManager.suspended.contains(pkg),"active startup preserves suspension");
        Settings.Global.lowPower=0;EssentialApps.recoverIfInactive(c);
        check(!IPackageManager.suspended.contains(pkg)&&read(pending)==0,"inactive startup recovers pending suspension");
        Settings.Global.lowPower=1;
        data=app(pkg,false);EssentialApps.adjust(c,data);click(data); // ON
        IPackageManager.reject=true;CompoundButton rejected=click(data);
        check(data.e&&rejected.checked&&read(off)==0,"rejected OFF rolls back saved and UI state");
        check(read(pending)==0,"rejected OFF cleans journal after verified restoration");
        IPackageManager.reject=false;Settings.Secure.failKey=off;click(data);
        check(data.e&&read(off)==0&&!IPackageManager.suspended.contains(pkg),"failed settings write does not suspend");
        Settings.Secure.failKey=null;
        for(String fixed:new String[]{"com.samsung.android.sm_cn","com.android.settings","com.sec.android.app.clockpackage","com.teslacoilsw.launcher"}){
            EbsAppData d=app(fixed,true);EssentialApps.adjust(c,d);View row=new View(c);CompoundButton b=new CompoundButton(c);
            row.setOnClickListener(v->{});EssentialApps.bind(row,b,d);
            check(!d.d&&d.e&&b.visibility==View.GONE&&!row.clickable&&row.listener==null,"fixed core: "+fixed);
        }
        EbsAppData own=app(pkg,false);EssentialApps.adjust(c,own);View row=new View(c);CompoundButton reused=new CompoundButton(c);
        EssentialApps.bind(row,reused,own);reused.setOnClickListener(v->{});
        EbsAppData ordinary=app("com.example.app",true);row.setOnClickListener(v->{});
        View.OnClickListener stock=row.listener;EssentialApps.bind(row,reused,ordinary);
        check(row.listener==stock&&reused.listener==null&&!reused.clickable,"recycled ordinary row retains stock click");
        PackageManagerService service=new PackageManagerService();service.mContext=c;write(off,1);
        check(!MaximumSuspendPolicy.keepProtection(service,pkg,10000,0,true),"scoped PMS exception");
        check(!MaximumSuspendPolicy.keepProtection(service,pkg,1000,0,true),"actual shared-system UID supported");
        check(MaximumSuspendPolicy.isManager("com.samsung.android.sm_cn")&&!MaximumSuspendPolicy.isManager("android"),"named suspender gate");
        check(MaximumSuspendPolicy.keepProtection(service,pkg,10000,10,true),"PMS cross-user refused");
        c.pm.signed=false;check(MaximumSuspendPolicy.keepProtection(service,pkg,10000,0,true),"unsigned caller refused");c.pm.signed=true;
        c.pm.manager=false;check(MaximumSuspendPolicy.keepProtection(service,pkg,10000,0,true),"different caller refused");c.pm.manager=true;
        Settings.Global.lowPower=0;check(MaximumSuspendPolicy.keepProtection(service,pkg,10000,0,true),"outside Maximum keeps protection");Settings.Global.lowPower=1;
        c.pm.home=pkg;check(MaximumSuspendPolicy.keepProtection(service,pkg,10000,0,true),"current launcher always protected");
        check(android.widget.Toast.shown>=2,"errors visible");
        System.out.println("PASS "+assertions+" helper transaction, restoration, UI binding and PMS assertions");
    }
}
'''

with tempfile.TemporaryDirectory(prefix="unica-maximum-host-") as folder:
    work = Path(folder)
    for name, text in STUBS.items():
        file = work / name
        file.parent.mkdir(parents=True, exist_ok=True)
        file.write_text(text)
    (work / "HelperTest.java").write_text(HARNESS)
    sources = sorted(work.rglob("*.java")) + sorted((ROOT / "java").rglob("*.java"))
    subprocess.run(["javac", "-encoding", "UTF-8", "-d", str(work / "classes"), *map(str, sources)], check=True)
    subprocess.run(["java", "-cp", str(work / "classes"), "HelperTest"], check=True)
