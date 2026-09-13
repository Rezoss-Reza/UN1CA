/* SPDX-License-Identifier: AGPL-3.0-or-later */
package io.mesalabs.unica.settings.spoof;

import android.app.AlertDialog;
import android.content.*;
import android.content.pm.*;
import android.os.*;
import android.provider.Settings;
import androidx.preference.Preference;
import androidx.preference.TwoStatePreference;
import com.android.settings.SettingsPreferenceFragment;
import com.android.settings.search.BaseSearchIndexProvider;
import io.mesalabs.unica.utils.Utils;
import java.io.*;
import java.util.*;
import org.json.*;

/** Retains the registered fragment name so existing Settings gateway links remain valid. */
public final class HideDeveloperStatusFragment extends SettingsPreferenceFragment implements Preference.OnPreferenceClickListener {
    public static final BaseSearchIndexProvider SEARCH_INDEX_DATA_PROVIDER = new BaseSearchIndexProvider(Utils.getResourceId("xml", "unica_hma_privacy_settings"));
    private static final String KEY="unica_hma_policy";
    private JSONObject config;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private static final String[] APP_KEYS={"accessibility_apps","custom_rom","detector_apps","root_apps","shizuku_dhizuku","sus_apps","xposed"};
    private static final String[] APP_LABELS={"Accessibility apps","Custom ROM apps","Detector / checker apps","Root managers and rooted apps","Shizuku / Dhizuku apps","Suspicious apps","LSPosed / Xposed modules"};
    private static final String[] SETTING_KEYS={"accessibility","dev_options","input_method"};
    private static final String[] SETTING_LABELS={"Accessibility — hide settings and service lists","Developer options — hide ADB, developer and mock-location settings","Input method — report Gboard and Google TTS; filter keyboard lists"};
    private static final String[] GID_KEYS={"1015","1023","1032","1077","1078","1079","3003","9997"};
    private static final String[] GID_LABELS={"SDCARD_RW (1015) — media write access","MEDIA_RW (1023) — media write access","PACKAGE_INFO (1032) — package details","EXTERNAL_STORAGE (1077) — USB storage access","EXT_DATA_RW (1078) — external app data","EXT_OBB_RW (1079) — external OBB data","INET (3003) — removes network access; apps may crash","SHARED_USER (9997) — may block all shared storage access"};
    private static final String[] LOG_KEYS={"package","activity","settings","other"};
    private static final String[] LOG_LABELS={"Package manager","Activity launch","Settings","Others (including Zygote restrictions)"};
    private static final String ENABLE_ALL="__enable_all_user_apps__";
    private static final String[] STORE_KEYS={"com.android.vending","com.sec.android.app.samsungapps"};
    private static final String[] STORE_LABELS={"Google Play Store","Samsung Galaxy Store"};

    public int getMetricsCategory() { return 744; }
    public void onCreate(Bundle state) {
        super.onCreate(state);
        addPreferencesFromResource(Utils.getResourceId("xml","unica_hma_privacy_settings"));
        try {
            String raw=Settings.Secure.getString(getActivity().getContentResolver(),KEY);
            config=new JSONObject(raw==null ? "{}" : raw);
            if(raw==null) {
                // Preserve lists without silently enabling new restrictions for every installed app.
                config.put("targets",legacyList("HideDeveloperStatusUtils", "unica_hide_dev_list"));
                config.put("hidden",legacyList("HideAppListUtils", "unica_hma_list"));
                config.put("enabled",false);
                config.put("activity",true);
                config.put("risky",true);
                config.put("logging",true);
                config.put("logs",new JSONArray(Arrays.asList(LOG_KEYS)));
            }
        } catch(JSONException e) { config=new JSONObject(); }
        for(String key:new String[]{"enabled","targets","hidden","playUpdate","unknown","activity","invertActivity","apps","settings","risky","gids","logging","logs","viewLogs"}) findPreference("hma_"+key).setOnPreferenceClickListener(this);
        refresh();
    }
    private JSONArray csv(String text) {
        JSONArray a=new JSONArray();
        if(text!=null) for(String name:text.split(",")) if(!name.trim().isEmpty()) a.put(name.trim());
        return a;
    }
    private JSONArray legacyList(String helper, String key) {
        try {
            Object list=Class.forName("io.mesalabs.unica."+helper).getMethod("getApps",ContentResolver.class).invoke(null,getActivity().getContentResolver());
            return new JSONArray((Collection<?>)list);
        } catch(Exception e) { return csv(Settings.Secure.getString(getActivity().getContentResolver(),key)); }
    }
    private Set<String> selected(String key) {
        Set<String> result=new HashSet<String>();
        JSONArray a=config.optJSONArray(key);
        if(a!=null) for(int i=0;i<a.length();i++) result.add(a.optString(i));
        return result;
    }
    private JSONObject map(String key) {
        JSONObject map=config.optJSONObject(key);
        return map==null ? new JSONObject() : map;
    }
    private boolean defaultOn(String key) {
        return "activity".equals(key) || "risky".equals(key) || "logging".equals(key);
    }
    private boolean userApp(ApplicationInfo a) {
        int appId=a.uid%100000;
        return appId>=10000 && appId<20000 && (a.flags&(ApplicationInfo.FLAG_SYSTEM|ApplicationInfo.FLAG_UPDATED_SYSTEM_APP))==0 && !a.packageName.equals("com.android.settings") && !a.packageName.equals("com.android.systemui");
    }
    private void save() {
        if(!Settings.Secure.putString(getActivity().getContentResolver(),KEY,config.toString())) {
            new AlertDialog.Builder(getActivity()).setTitle("Could not save HMA settings").setMessage("The settings provider rejected this change.").setPositiveButton(android.R.string.ok,null).show();
        }
        refresh();
    }
    private void refresh() {
        for(String key:new String[]{"enabled","activity","risky","logging"}) {
            Preference pref=findPreference("hma_"+key);
            boolean checked=config.optBoolean(key,defaultOn(key));
            pref.setSummary(checked ? "On" : "Off");
            if(pref instanceof TwoStatePreference) ((TwoStatePreference)pref).setChecked(checked);
        }
        for(String key:new String[]{"targets","hidden","playUpdate","invertActivity","apps","settings","gids","logs"}) findPreference("hma_"+key).setSummary(selected(key).size()+" selected");
        findPreference("hma_unknown").setSummary(map("installerSpoof").length()+" selected");
    }
    private void toggle(String key, Preference pref) {
        try {
            boolean checked=pref instanceof TwoStatePreference ? ((TwoStatePreference)pref).isChecked() : !config.optBoolean(key,defaultOn(key));
            config.put(key,checked);
            save();
        }
        catch(JSONException ignored) {}
    }
    public boolean onPreferenceClick(Preference p) {
        String key=p.getKey().substring(4);
        if(Arrays.asList("enabled","activity","risky","logging").contains(key)) toggle(key,p);
        else if("apps".equals(key)) choices(key,"Application presets",APP_KEYS,APP_LABELS,null);
        else if("settings".equals(key)) choices(key,"Settings presets",SETTING_KEYS,SETTING_LABELS,null);
        else if("gids".equals(key)) new AlertDialog.Builder(getActivity()).setTitle("Restrict Zygote permissions").setMessage("Experimental. These choices remove supplementary groups from selected apps at their next process start. Network or storage access may stop working and apps may crash. Restart selected apps after changing this setting.").setNegativeButton(android.R.string.cancel,null).setPositiveButton("Continue",new DialogInterface.OnClickListener(){ public void onClick(DialogInterface d,int w){ choices("gids","Permissions to remove",GID_KEYS,GID_LABELS,null); }}).show();
        else if("logs".equals(key)) choices(key,"Filter log categories",LOG_KEYS,LOG_LABELS,null);
        else if("viewLogs".equals(key)) showLogs();
        else if("unknown".equals(key)) unknownChoices();
        else appChoices(key);
        return true;
    }
    private void choices(final String key,String title,final String[] values,String[] labels, final Set<String> retained) {
        choices(key,title,values,labels,retained,false);
    }
    private void choices(final String key,String title,final String[] values,String[] labels, final Set<String> retained, final boolean enableAll) {
        final Set<String> chosen=selected(key);
        final boolean[] checked=new boolean[values.length];
        for(int i=0;i<values.length;i++) checked[i]=enableAll && i==0 ? chosen.containsAll(Arrays.asList(values).subList(1,values.length)) : chosen.contains(values[i]);
        new AlertDialog.Builder(getActivity()).setTitle(title).setMultiChoiceItems(labels,checked,new DialogInterface.OnMultiChoiceClickListener(){public void onClick(DialogInterface d,int i,boolean value){checked[i]=value;}}).setNegativeButton(android.R.string.cancel,null).setPositiveButton("Apply",new DialogInterface.OnClickListener(){public void onClick(DialogInterface d,int w){
            Set<String> result=retained==null ? new TreeSet<String>() : new TreeSet<String>(retained);
            for(int i=enableAll ? 1 : 0;i<values.length;i++) if(checked[i]) result.add(values[i]);
            try { config.put(key,new JSONArray(result)); save(); } catch(JSONException ignored) {}
        }}).show().getListView().setOnItemClickListener(new android.widget.AdapterView.OnItemClickListener(){public void onItemClick(android.widget.AdapterView<?> parent, android.view.View view, int i, long id){
            checked[i]=((android.widget.ListView)parent).isItemChecked(i);
            if(enableAll && i==0) for(int j=1;j<checked.length;j++){checked[j]=checked[0];((android.widget.ListView)parent).setItemChecked(j,checked[0]);}
            else if(enableAll) { boolean all=true; for(int j=1;j<checked.length;j++) if(!checked[j]) all=false; checked[0]=all; ((android.widget.ListView)parent).setItemChecked(0,all); }
        }});
    }
    private void appChoices(final String key) {
        final Context c=getActivity();
        final Set<String> targets=selected("targets");
        final Set<String> previous=selected(key);
        new Thread(new Runnable(){public void run(){
            try {
                final PackageManager pm=c.getPackageManager();
                List<ApplicationInfo> apps=new ArrayList<ApplicationInfo>();
                for(ApplicationInfo a:pm.getInstalledApplications(0)) {
                    if(!userApp(a)) continue;
                    if("invertActivity".equals(key) && !targets.contains(a.packageName)) continue;
                    apps.add(a);
                }
                Collections.sort(apps,new Comparator<ApplicationInfo>(){public int compare(ApplicationInfo a,ApplicationInfo b){return pm.getApplicationLabel(a).toString().compareToIgnoreCase(pm.getApplicationLabel(b).toString());}});
                final boolean enableAll="targets".equals(key);
                final int offset=enableAll ? 1 : 0;
                final String[] values=new String[apps.size()+offset],labels=new String[apps.size()+offset];
                if(enableAll) { values[0]=ENABLE_ALL; labels[0]="Enable All"; }
                final Set<String> retained=new HashSet<String>(previous);
                for(int i=0;i<apps.size();i++){ApplicationInfo a=apps.get(i); values[i+offset]=a.packageName; labels[i+offset]=pm.getApplicationLabel(a)+"\n"+a.packageName; retained.remove(a.packageName);}
                handler.post(new Runnable(){public void run(){if(isAdded()) choices(key,"hidden".equals(key)?"App to hide":"playUpdate".equals(key)?"Prevent Play Store update":"invertActivity".equals(key)?"Invert activity protection for":"App to spoof",values,labels,retained,enableAll);}});
            } catch(final RuntimeException e) { handler.post(new Runnable(){public void run(){if(isAdded()) new AlertDialog.Builder(getActivity()).setMessage("Could not load installed apps.").setPositiveButton(android.R.string.ok,null).show();}}); }
        }},"HMA-app-picker").start();
    }
    private String installSource(PackageManager pm, String name) {
        try {
            Object info=pm.getClass().getMethod("getInstallSourceInfo",String.class).invoke(pm,name);
            if(info==null) return null;
            Object installing=info.getClass().getMethod("getInstallingPackageName").invoke(info);
            Object initiating=info.getClass().getMethod("getInitiatingPackageName").invoke(info);
            return installing instanceof String ? (String)installing : initiating instanceof String ? (String)initiating : null;
        } catch(Exception e) { return ""; }
    }
    private void unknownChoices() {
        final Context c=getActivity();
        final JSONObject current=map("installerSpoof");
        new Thread(new Runnable(){public void run(){
            try {
                final PackageManager pm=c.getPackageManager();
                List<ApplicationInfo> apps=new ArrayList<ApplicationInfo>();
                for(ApplicationInfo a:pm.getInstalledApplications(0)) if(userApp(a) && installSource(pm,a.packageName)==null) apps.add(a);
                Collections.sort(apps,new Comparator<ApplicationInfo>(){public int compare(ApplicationInfo a,ApplicationInfo b){return pm.getApplicationLabel(a).toString().compareToIgnoreCase(pm.getApplicationLabel(b).toString());}});
                final String[] values=new String[apps.size()],labels=new String[apps.size()];
                final boolean[] checked=new boolean[apps.size()];
                for(int i=0;i<apps.size();i++){ApplicationInfo a=apps.get(i); values[i]=a.packageName; String store=current.optString(a.packageName,null); checked[i]=store!=null; labels[i]=pm.getApplicationLabel(a)+"\n"+a.packageName+(store==null?"":"\n"+("com.sec.android.app.samsungapps".equals(store)?STORE_LABELS[1]:STORE_LABELS[0]));}
                handler.post(new Runnable(){public void run(){if(!isAdded()) return; new AlertDialog.Builder(getActivity()).setTitle("Unknown Installed").setMultiChoiceItems(labels,checked,new DialogInterface.OnMultiChoiceClickListener(){public void onClick(DialogInterface d,int i,boolean value){checked[i]=value;}}).setNegativeButton(android.R.string.cancel,null).setPositiveButton("Apply",new DialogInterface.OnClickListener(){public void onClick(DialogInterface d,int w){
                    final Set<String> result=new TreeSet<String>();
                    for(int i=0;i<values.length;i++) if(checked[i]) result.add(values[i]);
                    if(result.isEmpty()) { try{config.put("installerSpoof",new JSONObject());save();}catch(JSONException ignored){}; return; }
                    new AlertDialog.Builder(getActivity()).setTitle("Spoof installation source").setItems(STORE_LABELS,new DialogInterface.OnClickListener(){public void onClick(DialogInterface d,int which){
                        try{JSONObject map=new JSONObject(); for(String pkg:result) map.put(pkg,STORE_KEYS[which]); config.put("installerSpoof",map); save();}catch(JSONException ignored){}
                    }}).show();
                }}).show();}});
            } catch(final RuntimeException e) { handler.post(new Runnable(){public void run(){if(isAdded()) new AlertDialog.Builder(getActivity()).setMessage("Could not load unknown installed apps.").setPositiveButton(android.R.string.ok,null).show();}}); }
        }},"HMA-unknown-picker").start();
    }
    private void showLogs() {
        final Set<String> categories=selected("logs");
        final int user=android.os.Process.myUid()/100000;
        new Thread(new Runnable(){public void run(){
            final StringBuilder text=new StringBuilder();
            try {
                java.lang.Process process=new ProcessBuilder("logcat","-d","-t","1000","-v","brief","UnicaHMA:I","*:S").redirectErrorStream(true).start();
                BufferedReader reader=new BufferedReader(new InputStreamReader(process.getInputStream()));
                try { String line; while((line=reader.readLine())!=null) {
                    int start=line.indexOf("uid=");
                    if(start<0) continue;
                    int end=line.indexOf(' ',start);
                    int uid=Integer.parseInt(line.substring(start+4,end<0?line.length():end));
                    if(uid/100000!=user) continue;
                    for(String cat:categories) if(line.contains("["+cat+"]")){text.append(line).append('\n');break;}
                    if(text.length()>60000) text.delete(0,text.length()-60000);
                }} finally { reader.close(); process.destroy(); }
            } catch(Exception e) {text.append("Could not read HMA logs: ").append(e.getMessage());}
            handler.post(new Runnable(){public void run(){if(isAdded()) new AlertDialog.Builder(getActivity()).setTitle("HMA interception logs").setMessage(text.length()==0?"No matching events in the recent log buffer. Enable logging and use a protected app first.":text.toString()).setPositiveButton(android.R.string.ok,null).show();}});
        }},"HMA-log-reader").start();
    }
}
