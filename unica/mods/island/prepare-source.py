#!/usr/bin/env python3
# Copyright (c) 2026 UN1CA contributors
# SPDX-License-Identifier: GPL-3.0-or-later
"""Adapt pinned upstream Java in a temporary tree; never rewrite the vendored snapshot."""
from pathlib import Path
import json,re,shutil,sys,xml.etree.ElementTree as ET
here=Path(__file__).resolve().parent
out=Path(sys.argv[1]);src=here/'upstream/source';base=src/'java/dev/android/dynamicisland'
def put(rel,text):
 p=out/rel;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(text)
def method(text,signature,new):
 start=text.index(signature); brace=text.index('{',start);depth=1;i=brace+1
 # Upstream methods below contain no braces in string literals except documented cases.
 while depth:
  if text[i]=='{':depth+=1
  elif text[i]=='}':depth-=1
  i+=1
 return text[:start]+new+text[i:]
for p in base.rglob('*.java'):
 rel=p.relative_to(src/'java');t=p.read_text()
 if '/xposed/' in str(p) and p.name not in {'Diagnostics.java','DarkRemote.java'}:continue
 if p.name in {'SettingsProvider.java','SystemUiRestarter.java'}:continue
 if p.name=='ModuleSettings.java':
  t=t.replace('import de.robv.android.xposed.XSharedPreferences;','')
  t=t.replace('"dev.android.dynamicisland"','"com.android.settings"')
  t=t.replace('private static XSharedPreferences prefs;','private static GlobalPrefs prefs;')
  t=method(t,'public static synchronized void ensureStarted()', '''public static synchronized void ensureStarted() {
        if (started) return;
        started=true; refresh();
        android.os.HandlerThread thread=new android.os.HandlerThread("NativeIslandPrefs"); thread.start();
        Handler handler=new Handler(thread.getLooper());
        GlobalPrefs.context().getContentResolver().registerContentObserver(
            android.provider.Settings.Global.getUriFor(GlobalPrefs.KEY),false,
            new android.database.ContentObserver(handler) { @Override public void onChange(boolean self) { refresh(); } });
    }''')
  t=method(t,'private static SharedPreferences readSource()', '''private static SharedPreferences readSource() {
        if(prefs==null)prefs=new GlobalPrefs(GlobalPrefs.context());
        return new MapPrefs(new java.util.HashMap<>(prefs.getAll()));
    }''')
  t=method(t,'private static Map<String, Object> readProvider()', 'private static Map<String,Object> readProvider() { return null; }')
  t=method(t,'public static SharedPreferences openStore(android.content.Context ctx)', 'public static SharedPreferences openStore(android.content.Context ctx) { GlobalPrefs.bind(ctx); return new GlobalPrefs(ctx); }')
  t=t.replace('Diagnostics.fact("settings file", prefs.getFile() + " readable=" + prefs.getFile().canRead() + " keys=" + prefs.getAll().size());','Diagnostics.fact("settings storage", "native Settings.Global");')
  t=t.replace('viaProvider ? "provider (" + source.getAll().size() + " keys)" : "preference file (" + source.getAll().size() + " keys)"','"native ROM configuration"')
  # Independent event callbacks can remain registered once per process but are inert while OFF.
  for name in ['eventCharging','eventRinger','eventBluetooth','eventToggles','privacyIndicator','privacyLocation']:
   t=t.replace('return '+name+';', 'return '+name+' && android.provider.Settings.Global.getInt(GlobalPrefs.context().getContentResolver(),"unica_dynamic_island",0)==1;')
 if p.name=='SettingsActivity.java':
  t=t.replace('super.onCreate(savedInstanceState);','super.onCreate(savedInstanceState);\n        GlobalPrefs.bind(this);')
  a=t.index('        LinearLayout hero = ui.hero(page);');b=t.index('        ui.category(page, getString(R.string.hub_settings));',a)
  t=t[:a]+'''        ui.backButton(page,getString(R.string.back),this::goBack);
        LinearLayout master=ui.card(page);
        ui.switchRow(master,"Enable Dynamic Island","Turn off to restore the standard status bar.",
            android.provider.Settings.Global.getInt(getContentResolver(),"unica_dynamic_island",0)==1,
            on -> android.provider.Settings.Global.putInt(getContentResolver(),"unica_dynamic_island",on?1:0));

'''+t[b:]
  t=t.replace('        ui.actionRow(tools, getString(R.string.restart_systemui), getString(R.string.restart_systemui_sum), this::confirmRestart);','')
  t=method(t,'private void restartSystemUi()', 'private void restartSystemUi() { /* Native settings apply through the observer. */ }')
  # Rebuild current page if the backend automatically turns the master switch OFF.
  t=t.replace('    private SharedPreferences prefs;','''    private final android.database.ContentObserver masterObserver=new android.database.ContentObserver(new android.os.Handler(android.os.Looper.getMainLooper())) {
        @Override public void onChange(boolean self) { if(!isFinishing() && !stack.isEmpty())render(); }
    };
    @Override protected void onDestroy() { getContentResolver().unregisterContentObserver(masterObserver); super.onDestroy(); }
    private SharedPreferences prefs;''')
  t=t.replace('        open(this::hub);','        getContentResolver().registerContentObserver(android.provider.Settings.Global.getUriFor("unica_dynamic_island"),false,masterObserver);\n        open(this::hub);')
  t=t.replace('        setContentView(scroll);','''        setContentView(scroll);
        if(android.provider.Settings.System.getInt(getContentResolver(),"unica_settings_bg_transparency_enabled",0)!=0) {
            getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER);
            getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT));
            scroll.setBackgroundColor(android.graphics.Color.TRANSPARENT);
        }''')
  t=t.replace('        ui.actionRow(about, getString(R.string.hub_credits), getString(R.string.hub_credits_text), null);','''        ui.actionRow(about, getString(R.string.hub_credits), getString(R.string.hub_credits_text), null);
        ui.actionRow(about,"Open-source license","MIT · sampple-korea", () -> new android.app.AlertDialog.Builder(this)
            .setTitle("MIT License").setMessage(getString(GlobalPrefs.resource("license_text")))
            .setPositiveButton(android.R.string.ok,null).show());''')
  # Do not display the host Settings package version as the upstream version.
  t=method(t,'private String version()', 'private String version() { return "0.1.11-dev11 · native ROM"; }')
 if p.name=='Diagnostics.java':t=t.replace('CrashGuard.BUILD','"0.1.11-dev11 native"')
 if p.name=='RuleStore.java':t=t.replace('RuleStore.class.getResourceAsStream(path)','BuiltinRules.open(path)')
 if p.name=='IslandController.java':
  t=t.replace('    public void requestRefresh() {','    public void requestRefresh() {\n        if(!NativeBridge.shouldRun())return;')
  t=t.replace('    private void refreshNow() {','    private void refreshNow() {\n        if(!NativeBridge.shouldRun()) { refreshPosted=false; return; }')
  t=t.replace('            XLog.once("IslandController.refreshNow", t);','            XLog.once("IslandController.refreshNow", t);\n            NativeBridge.fail(t);')
  t=t.replace('new IntentFilter(ModuleSettings.ACTION_PILL_PREVIEW), Context.RECEIVER_EXPORTED);','new IntentFilter(ModuleSettings.ACTION_PILL_PREVIEW), "android.permission.WRITE_SECURE_SETTINGS", main, Context.RECEIVER_EXPORTED);')
  # Only create a window from a real status bar; no delayed unowned fallback window.
  t=t.replace('        placement.scheduleFallbackWindow();','')
 if p.name=='IslandPlacement.java':
  t=t.replace('    void ensureWindow(boolean withoutStatusBar) {','    void ensureWindow(boolean withoutStatusBar) {\n        if(!NativeBridge.shouldRun())return;')
  t=t.replace('import android.', 'import android.',1) # explicit package-independent call below
  t=t.replace('public final class IslandPlacement','public final class IslandPlacement')
  i=t.rfind('}')
  t=t[:i]+'''    public void nativeStop() {
        if(statusBarView!=null)statusBarDetached(statusBarView);
        if(window!=null)window.detach();
        window=null; view=null; revealPending=false;
        main.removeCallbacks(revealTimer);
    }
'''+t[i:]
 if p.name=='IslandWindow.java':t=t.replace('    void detach() {','    public void detach() {')
 if p.name=='MediaSource.java':
  a=t.index('        output = new OutputSource(');b=t.index('        try {\n            MediaSessionManager manager',a)
  t=t[:a]+'        if(output==null) {\n'+t[a:b]+'        }\n'+t[b:]
  t=t.replace('    public void start() {','    private boolean nativeListening;\n    public void start() {\n        if(nativeListening)return; nativeListening=true;')
  i=t.rfind('}')
  t=t[:i]+'''    public void nativePause() {
        ctx.getSystemService(MediaSessionManager.class).removeOnActiveSessionsChangedListener(sessionsListener);
        for(Session s:sessions.values())s.release();
        sessions.clear(); nativeListening=false;
    }
    public void nativeResume() { start(); }
'''+t[i:]
 if p.name=='OneUi.java':
  t=t.replace('background = dark ? 0xff000000 : 0xfff3f3f6;', 'background = dark ? 0xff000000 : 0xfff3f3f6;')
  t=t.replace('cardColor = dark ? 0xff171719 : 0xffffffff;','''boolean transparent=android.provider.Settings.System.getInt(ctx.getContentResolver(),"unica_settings_bg_transparency_enabled",0)!=0;
        int level=Math.max(70,Math.min(100,android.provider.Settings.System.getInt(ctx.getContentResolver(),"unica_settings_bg_transparency_level",70)));
        int alpha=transparent?Math.round(255*(100-level)/100f):255;
        cardColor = (dark ? 0x00171719 : 0x00ffffff) | (alpha<<24);''')
  # Respect platform theme accents instead of hardcoded blue.
  t=t.replace('accent = dark ? 0xff5ea3ff : 0xff0a6cff;','android.util.TypedValue value=new android.util.TypedValue();\n        ctx.getTheme().resolveAttribute(android.R.attr.colorAccent,value,true);\n        accent=value.resourceId!=0?ctx.getColor(value.resourceId):value.data;')
 put(rel,t)
# Explicit native overrides; compiled without any Xposed APIs or stub runtime.
for p in (here/'java/dev/android').rglob('*.java'):put(p.relative_to(here/'java'),p.read_text())
for p in (here/'java/io').rglob('*.java'):put(p.relative_to(here/'java'),p.read_text())
# Resource names are prefixed in Settings to avoid collisions with Samsung strings.
resources=ET.parse(src/'res/values/strings.xml').getroot()
fields='\n'.join('        public static final int '+e.attrib['name']+'=dev.android.dynamicisland.settings.GlobalPrefs.resource("'+e.attrib['name']+'");' for e in resources if e.tag=='string')
put(Path('dev/android/dynamicisland/R.java'),'package dev.android.dynamicisland;\npublic final class R { public static final class string {\n'+fields+'\n}}\n')
# Rule resources are embedded: APK rebuild/DEX replacement cannot drop them.
constants=[];select=[]
for i,p in enumerate(sorted((src/'resources/dev/android/dynamicisland/rules').glob('*.json'))):
 constants.append('private static final String DATA'+str(i)+'='+json.dumps(p.read_text(),ensure_ascii=True)+';')
 select.append('if(path.endsWith("'+p.name+'"))return new java.io.ByteArrayInputStream(DATA'+str(i)+'.getBytes(java.nio.charset.StandardCharsets.UTF_8));')
put(Path('dev/android/dynamicisland/island/rules/BuiltinRules.java'),'package dev.android.dynamicisland.island.rules;\nfinal class BuiltinRules {\n'+'\n'.join(constants)+'\nstatic java.io.InputStream open(String path){'+'\n'.join(select)+'return null;}\n}\n')
print('Prepared',len(list(out.rglob('*.java'))),'native Java files in',out)
