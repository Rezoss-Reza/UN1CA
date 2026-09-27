/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.mesalabs.unica.battery;

import android.app.*;
import android.content.*;
import android.content.pm.ApplicationInfo;
import android.net.Uri;
import android.os.*;
import android.view.*;
import android.widget.*;
import org.json.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;

/** Settings-owned UI; privileged operations are performed by the ROM init worker. */
public final class BatteryActivity extends Activity {
    private JSONObject profile, response;
    private JSONArray caps=new JSONArray();
    private LinearLayout page;
    private boolean busy, ready;
    private String status="Checking available controls…";
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private static final int EXPORT=1, IMPORT=2;
    private int dp(int n) {return Math.round(n*getResources().getDisplayMetrics().density);}
    @Override public void onCreate(Bundle state) {
        super.onCreate(state); setTitle("Battery Saving");
        if(getActionBar()!=null) getActionBar().setDisplayHomeAsUpEnabled(true);
        try {
            String saved=getPreferences(0).getString("draft",null);
            profile=saved==null?Profile.empty():Profile.validate(new JSONObject(saved));
        } catch(Exception e) {try {profile=Profile.empty();} catch(Exception impossible) {throw new IllegalStateException(impossible);}}
        draw(); run("probe",null);
    }
    @Override public boolean onOptionsItemSelected(MenuItem item) {if(item.getItemId()==android.R.id.home){finish();return true;}return super.onOptionsItemSelected(item);}
    @Override public void onBackPressed() {if(busy){Toast.makeText(this,"Wait for the battery operation to finish",Toast.LENGTH_SHORT).show();return;}super.onBackPressed();}
    @Override protected void onDestroy(){worker.shutdown();super.onDestroy();}
    private void save(){getPreferences(0).edit().putString("draft",profile.toString()).apply();}
    private void custom(){try{profile.put("preset","Custom");save();}catch(Exception ignored){}}
    private void text(String s,int size){TextView v=new TextView(this);v.setText(s);v.setTextSize(size);v.setPadding(0,dp(7),0,dp(7));page.addView(v);}
    private Button button(String label,boolean enabled,View.OnClickListener listener){Button b=new Button(this);b.setText(label);b.setAllCaps(false);b.setEnabled(enabled&&!busy);b.setOnClickListener(listener);page.addView(b);return b;}
    private void draw(){
        ScrollView scroll=new ScrollView(this);page=new LinearLayout(this);page.setOrientation(LinearLayout.VERTICAL);page.setPadding(dp(20),dp(12),dp(20),dp(24));scroll.addView(page);setContentView(scroll);
        scroll.setOnApplyWindowInsetsListener((v,insets)->{android.graphics.Insets bars=insets.getInsets(WindowInsets.Type.systemBars());v.setPadding(bars.left,bars.top,bars.right,bars.bottom);return insets;});
        Switch master=new Switch(this);master.setText("Enable Battery Saving");master.setChecked(profile.optBoolean("enabled"));master.setEnabled(ready&&!busy);page.addView(master);
        text("Turning this off restores the values saved before this feature changed them. Changes below take effect after Save and apply.",13);
        master.setOnCheckedChangeListener((b,on)->{
            try{profile.put("enabled",on);save();}catch(Exception ignored){}
            if(on && "NOSLEN".equals(profile.optString("preset"))) confirmNoslen(()->run("apply",profile),()->{try{profile.put("enabled",false);save();}catch(Exception ignored){}draw();});
            else run("apply",profile);
        });
        text(status,14);
        button("Refresh device status",true,v->run("probe",null));
        text("Preset: "+profile.optString("preset"),18);
        text("Balanced preserves kernel and memory defaults and reports freezer state. Efficiency selects an advertised CPU ceiling near 85% of the original fastest-cluster limit; battery benefit needs measurement. Refresh-rate limiting stays optional. NOSLEN reproduces the module's intended settings.",13);
        for(String name:new String[]{"Balanced","Efficiency","NOSLEN"}) button(name,ready,v->{
            Runnable select=()->{try{
                boolean enabled=profile.getBoolean("enabled");
                JSONArray baseline=new JSONArray(caps.toString());
                for(int i=0;i<baseline.length();i++){JSONObject d=baseline.getJSONObject(i);if(d.has("original"))d.put("current",d.getString("original"));}
                profile=Profile.preset(name,baseline);profile.put("enabled",enabled);save();status="Preset selected. Review the controls, then Save and apply.";draw();
            }catch(Exception e){error(e);}};
            if(name.equals("NOSLEN")) confirmNoslen(select,()->{});else select.run();
        });
        text("Controls",20);
        for(int i=0;i<caps.length();i++)try{control(caps.getJSONObject(i));}catch(Exception e){text(e.getMessage(),13);}
        text("Targeted app restrictions",18);
        text("Only select apps whose background work you can limit. Restricting messaging, alarms, VPN, wearables or Samsung feature providers can break expected behavior. No apps are selected by Balanced or Efficiency.",13);
        appButton("packages","Choose apps to restrict");
        discrete("Standby bucket",new String[]{"10","20","30","40","45"},new String[]{"Active","Working set","Frequent","Rare","Restricted"},profile.optString("bucket","45"),value->{try{profile.put("bucket",value);custom();}catch(Exception ignored){}});
        text("Doze exemptions",18);
        text("Remove only unnecessary user-added exemptions. Essential messaging and service dependencies should retain them. System exemptions may be protected by Android.",13);
        appButton("exemptions","Choose exemptions to remove");
        button("Save and apply",ready,v->{if(profile.optBoolean("enabled")&&"NOSLEN".equals(profile.optString("preset")))confirmNoslen(()->run("apply",profile),()->{});else run("apply",profile);});
        text("Profile backup",18);
        text("Backups contain your choices, not device paths or original system values. Restore validates the file and leaves Battery Saving off until you enable it.",13);
        button("Back up profile",true,v->{save();Intent it=new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("application/json").addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE,"UN1CA-Battery-"+profile.optString("preset")+".json");startActivityForResult(it,EXPORT);});
        button("Restore profile",ready,v->startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("application/json").addCategory(Intent.CATEGORY_OPENABLE),IMPORT));
    }
    private void confirmNoslen(Runnable yes,Runnable no){new AlertDialog.Builder(this).setTitle("NOSLEN profile").setMessage("NOSLEN disables Wi-Fi runtime suspend and PCIe link power saving, disables Wi-Fi wakeup, sets swappiness to 60 and cached processes to 32, and restricts three Samsung apps. It may increase drain or delay background communication. Unsupported controls will be reported.").setPositiveButton("Continue",(d,w)->yes.run()).setNegativeButton("Cancel",(d,w)->no.run()).setOnCancelListener(d->no.run()).show();}
    interface Choice {void set(String value);}
    private void discrete(String title,String[] values,String[] labels,String selected,Choice change){
        int index=0;for(int i=0;i<values.length;i++)if(values[i].equals(selected))index=i;
        TextView value=new TextView(this);value.setText(title+": "+labels[index]);page.addView(value);
        SeekBar bar=new SeekBar(this);bar.setMax(values.length-1);bar.setProgress(index);bar.setEnabled(ready&&!busy&&values.length>1);bar.setContentDescription(title);page.addView(bar);
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){public void onStartTrackingTouch(SeekBar b){}public void onStopTrackingTouch(SeekBar b){change.set(values[b.getProgress()]);}public void onProgressChanged(SeekBar b,int p,boolean user){value.setText(title+": "+labels[p]);}});
    }
    private String label(String id,String v){
        if(v.equals("null"))return "Android default";
        if(v.equals("Unavailable"))return v;
        if(v.equals("__original"))return "Keep original";
        if(id.startsWith("cpu_"))return String.format(Locale.US,"%.0f MHz",Double.parseDouble(v)/1000);
        if(id.equals("gpu"))return String.format(Locale.US,"%.1f MHz",Double.parseDouble(v)/1000000);
        if(id.equals("refresh"))return v+" Hz";
        if(v.equals("device_default"))return "Android default";
        return v;
    }
    private void control(JSONObject d)throws Exception{
        String id=d.getString("id");text(d.getString("label"),18);text(d.getString("help"),13);
        text("Current: "+label(id,d.optString("current"))+(d.has("effective")?" · "+d.optString("effective"):"")+(d.has("original")?" · Original: "+label(id,d.optString("original")):""),12);
        JSONObject selections=profile.getJSONObject("controls");
        if(!d.optBoolean("supported")){text("Unavailable on this device"+(selections.has(id)?" · Requested: "+selections.getString(id):""),13);button("Keep original",selections.has(id),v->{selections.remove(id);custom();draw();});return;}
        JSONArray a=d.getJSONArray("values");String[] values=new String[a.length()+1],labels=new String[a.length()+1];values[0]="__original";labels[0]="Keep original";
        boolean found=!selections.has(id);for(int i=0;i<a.length();i++){values[i+1]=a.getString(i);labels[i+1]=label(id,values[i+1]);if(values[i+1].equals(selections.optString(id)))found=true;}
        if(!found)text("Saved value "+selections.optString(id)+" is unavailable here. Choose a supported value before applying.",13);
        discrete("Value",values,labels,selections.optString(id,"__original"),value->{try{if(value.equals("__original"))selections.remove(id);else selections.put(id,value);custom();}catch(Exception e){error(e);}});
    }
    private void appButton(String field,String title){
        JSONArray selected=profile.optJSONArray(field);text(selected==null||selected.length()==0?"No apps selected":selected.toString(),12);
        button(title,ready,v->{
            status="Loading apps…";busy=true;draw();
            worker.execute(()->{
                List<ApplicationInfo> apps=getPackageManager().getInstalledApplications(0);
                apps.removeIf(a->a.packageName.equals("com.android.settings")||a.packageName.equals("com.android.systemui")||a.packageName.equals("com.google.android.gms"));
                apps.sort(Comparator.comparing(a->a.loadLabel(getPackageManager()).toString(),String.CASE_INSENSITIVE_ORDER));
                runOnUiThread(()->{if(isDestroyed())return;busy=false;draw();String[] names=new String[apps.size()];boolean[] checked=new boolean[apps.size()];Set<String> current=new HashSet<>();JSONArray old=profile.optJSONArray(field);for(int i=0;i<old.length();i++)current.add(old.optString(i));
                    for(int i=0;i<apps.size();i++){ApplicationInfo a=apps.get(i);names[i]=a.loadLabel(getPackageManager())+"\n"+a.packageName;checked[i]=current.contains(a.packageName);}
                    new AlertDialog.Builder(this).setTitle(title).setMultiChoiceItems(names,checked,(dlg,which,on)->checked[which]=on).setNegativeButton("Cancel",null).setPositiveButton("Save selection",(dlg,which)->{try{JSONArray chosen=new JSONArray();for(int i=0;i<apps.size();i++)if(checked[i])chosen.put(apps.get(i).packageName);if(chosen.length()>100)throw new IOException("Select at most 100 apps");profile.put(field,chosen);custom();status="Selection saved. Use Save and apply.";draw();}catch(Exception e){error(e);}}).show();
                });
            });
        });
    }
    private void error(Exception e){status="Could not complete: "+e.getMessage();draw();}
    private void run(String operation,JSONObject requested){
        if(busy)return;
        final String input=requested==null?null:requested.toString();
        busy=true;status="Checking battery service…";draw();
        worker.execute(()->{
            try{
                JSONObject reply=new BatteryEngine(getApplicationContext()).execute(operation,input==null?null:new JSONObject(input));
                if(reply.has("error"))throw new IOException(reply.getString("error"));
                runOnUiThread(()->{if(isDestroyed())return;busy=false;ready=true;response=reply;caps=reply.optJSONArray("capabilities");
                    try{
                        JSONObject actual=reply.getJSONObject("profile");
                        if(operation.equals("apply") || !getPreferences(0).contains("draft"))profile=actual;
                        else profile.put("enabled",actual.getBoolean("enabled"));
                        save();
                        StringBuilder message=new StringBuilder(reply.optBoolean("ok")?"Device status checked":"Some changes failed; review details below");
                        if(reply.optBoolean("pendingRestore"))message.append("\nRestoration incomplete. Retry with Battery Saving off.");
                        message.append("\nKernel: ").append(reply.optString("kernel"));
                        JSONArray notes=reply.optJSONArray("notes");for(int i=0;i<notes.length();i++)message.append("\n").append(notes.optString(i));
                        status=message.toString();draw();
                    }catch(Exception e){error(e);}
                });
            }catch(Exception e){runOnUiThread(()->{if(isDestroyed())return;busy=false;error(e);});}
        });
    }
    @Override protected void onActivityResult(int request,int result,Intent data){
        super.onActivityResult(request,result,data);if(result!=RESULT_OK||data==null||data.getData()==null)return;
        Uri uri=data.getData();
        try{
            if(request==EXPORT){try(OutputStream o=getContentResolver().openOutputStream(uri,"wt")){if(o==null)throw new IOException("Cannot open backup");o.write(Profile.validate(profile).toString(2).getBytes("UTF-8"));}status="Profile backed up.";draw();}
            else if(request==IMPORT){
                ByteArrayOutputStream b=new ByteArrayOutputStream();try(InputStream in=getContentResolver().openInputStream(uri)){if(in==null)throw new IOException("Cannot open profile");byte[] buf=new byte[4096];int n;while((n=in.read(buf))!=-1){b.write(buf,0,n);if(b.size()>65536)throw new IOException("Profile exceeds 64 KB");}}
                JSONObject imported=Profile.validate(new JSONObject(b.toString("UTF-8")));imported.put("enabled",false);
                new AlertDialog.Builder(this).setTitle("Restore profile?").setMessage("This replaces your selected controls and turns Battery Saving off, restoring any current changes. Review the imported values before enabling it.").setNegativeButton("Cancel",null).setPositiveButton("Restore",(d,w)->{profile=imported;save();run("apply",profile);}).show();
            }
        }catch(Exception e){error(e);}
    }
}
