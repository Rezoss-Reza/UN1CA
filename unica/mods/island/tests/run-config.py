#!/usr/bin/env python3
# Copyright (c) 2026 UN1CA contributors
# SPDX-License-Identifier: GPL-3.0-or-later
"""Test the real GlobalPrefs transport with JVM Android models and org.json.
Pass a local org.json 20250107 JAR; it is a test dependency and is never shipped.
"""
import ast,hashlib,subprocess,sys,tempfile
from pathlib import Path
mod=Path(__file__).resolve().parents[1]
jar=Path(sys.argv[1])
assert hashlib.sha256(jar.read_bytes()).hexdigest()=='85d4c1ab192d3117fd02c7fff1ec0fe63ade45cf56def7fe950ef060cf06e99f','Use org.json:json:20250107'
work=Path(tempfile.mkdtemp(prefix='unica-island-config-tests.'))
tree=ast.parse((mod/'tests/run.py').read_text())
models=next(ast.literal_eval(n.value) for n in tree.body if isinstance(n,ast.Assign) and any(isinstance(t,ast.Name) and t.id=='SOURCES' for t in n.targets))
keep=('android/os/','android/content/','android/database/','android/net/','android/provider/','android/util/')
models={k:v for k,v in models.items() if k.startswith(keep)}
models['android/content/Context.java']=models['android/content/Context.java'].replace(' public Context getApplicationContext()', ' public android.content.res.Resources getResources(){return new android.content.res.Resources();}\n public Context getApplicationContext()')
models['android/provider/Settings.java']=models['android/provider/Settings.java'].replace(' public static class Global {',''' public static class Global {
  public static final Map<String,String> texts=new HashMap<>();
  public static String getString(ContentResolver r,String k){return texts.get(k);}
  public static boolean putString(ContentResolver r,String k,String v){texts.put(k,v);r.notifyKey(k);return true;}
''')
models['android/widget/Toast.java']='''package android.widget;
public class Toast {public static final int LENGTH_LONG=1;public static int shown;
 public static Toast makeText(android.content.Context c,CharSequence s,int d){return new Toast();}
 public void show(){shown++;}}
'''
models['TransportTest.java']='''
import dev.android.dynamicisland.settings.GlobalPrefs;
import android.content.*;import android.provider.Settings;import java.util.*;
public class TransportTest {
 static void check(boolean ok){if(!ok)throw new AssertionError();}
 public static void main(String[] args){
  GlobalPrefs ui=new GlobalPrefs(new Context()),systemui=new GlobalPrefs(new Context());
  check(systemui.getBoolean("haptics",true));
  Set<String> apps=new HashSet<>(Arrays.asList("com.example.one","com.example.two"));
  check(ui.edit().putBoolean("haptics",false).putInt("pill_x_dp",-20)
   .putLong("pill_preview_until",1790000000000L).putFloat("sample",0.25f)
   .putString("rules_custom","{\\"smart_rules\\":[]}").putStringSet("apps_listed",apps).commit());
  check(!systemui.getBoolean("haptics",true));check(systemui.getInt("pill_x_dp",0)==-20);
  check(systemui.getLong("pill_preview_until",0)==1790000000000L);check(systemui.getFloat("sample",0)==0.25f);
  check(systemui.getStringSet("apps_listed",null).equals(apps));
  Set<String> copy=systemui.getStringSet("apps_listed",null);copy.clear();check(ui.getStringSet("apps_listed",null).size()==2);
  SharedPreferences.Editor first=ui.edit().putInt("a",1),second=ui.edit().putInt("b",2);
  check(first.commit()&&second.commit());check(systemui.getInt("a",0)==1&&systemui.getInt("b",0)==2);
  check(ui.edit().remove("sample").commit());check(systemui.getFloat("sample",9)==9);
  String saved=Settings.Global.getString(new Context().getContentResolver(),GlobalPrefs.KEY);
  check(!ui.edit().putString("rules_custom","x".repeat(140000)).commit());
  check(saved.equals(Settings.Global.getString(new Context().getContentResolver(),GlobalPrefs.KEY)));
  ui.edit().putString("rules_custom","x".repeat(140000)).apply();android.os.Handler.drain();check(android.widget.Toast.shown==1);
  check(ui.edit().clear().commit());check(systemui.getAll().isEmpty());
  Settings.Global.putString(new Context().getContentResolver(),GlobalPrefs.KEY,"broken JSON");
  check(systemui.getInt("pill_x_dp",5)==5);
  System.out.println("PASS shared config, typed values, false persistence, sets, merge, limits and malformed recovery");
 }
}
'''
files=[]
for name,text in models.items():
 p=work/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(text);files.append(p)
classes=work/'classes';classes.mkdir()
sdk=Path.home()/'Android/Sdk/platforms/android-37.0/android.jar'
cp=f'{jar}:{sdk}'
subprocess.run(['javac','-cp',cp,'-d',str(classes),str(mod/'java/dev/android/dynamicisland/settings/GlobalPrefs.java'),*map(str,files)],check=True)
subprocess.run(['java','-cp',f'{classes}:{cp}','TransportTest'],check=True)
print('Host configuration test artifacts:',work)
