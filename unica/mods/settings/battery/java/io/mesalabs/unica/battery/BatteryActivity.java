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
    private LinearLayout page, content;
    private final List<View> cards=new ArrayList<>();
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
    @Override protected void onResume(){super.onResume();applyBackground();}
    private int romColor(String name,int fallback){
        int id=getResources().getIdentifier(name,"color",getPackageName());
        return id==0?fallback:getColor(id);
    }
    private void applyBackground(){
        boolean dark=(getResources().getConfiguration().uiMode&0x30)==0x20;
        boolean transparent=android.provider.Settings.System.getInt(getContentResolver(),"unica_settings_bg_transparency_enabled",0)!=0;
        int level=Math.max(70,Math.min(100,android.provider.Settings.System.getInt(getContentResolver(),"unica_settings_bg_transparency_level",70)));
        int surface=romColor("sesl_background_color_"+(dark?"dark":"light"),dark?0xff17171a:0xffffffff);
        int background=romColor("sesl_round_and_bgcolor_"+(dark?"dark":"light"),dark?0xff000000:0xfff2f2f2);
        if(transparent){
            surface=(surface&0xffffff)|((255-level*255/100)<<24);
            background=android.graphics.Color.TRANSPARENT;
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER);
        }else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER);
        getWindow().setFormat(transparent?android.graphics.PixelFormat.TRANSLUCENT:android.graphics.PixelFormat.OPAQUE);
        getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(background));
        for(View card:cards){
            android.graphics.drawable.GradientDrawable shape=new android.graphics.drawable.GradientDrawable();
            shape.setColor(surface);shape.setCornerRadius(dp(28));card.setBackground(shape);
        }
    }
    private void card(){
        page=new LinearLayout(this);page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(18),dp(12),dp(18),dp(16));
        LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(-1,-2);params.bottomMargin=dp(16);
        content.addView(page,params);cards.add(page);
    }
    private void save(){getPreferences(0).edit().putString("draft",profile.toString()).apply();}
    private void custom(){try{profile.put("preset","Custom");save();}catch(Exception ignored){}}
    private void text(String s,int size){TextView v=new TextView(this);v.setText(s);v.setTextSize(size);v.setPadding(0,dp(7),0,dp(7));if(size>=18){android.content.res.TypedArray colors=obtainStyledAttributes(new int[]{android.R.attr.textColorPrimary});v.setTextColor(colors.getColorStateList(0));colors.recycle();}page.addView(v);}
    private Button button(String label,boolean enabled,View.OnClickListener listener){Button b=new Button(this);b.setText(label);b.setAllCaps(false);b.setEnabled(enabled&&!busy);b.setOnClickListener(listener);LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(-1,-2);params.topMargin=dp(4);params.bottomMargin=dp(4);b.setMinHeight(dp(48));page.addView(b,params);return b;}
    private void draw(){
        cards.clear();ScrollView scroll=new ScrollView(this);content=new LinearLayout(this);content.setOrientation(LinearLayout.VERTICAL);content.setPadding(dp(12),dp(12),dp(12),dp(24));scroll.addView(content);setContentView(scroll);card();
        scroll.setOnApplyWindowInsetsListener((v,insets)->{android.graphics.Insets bars=insets.getInsets(WindowInsets.Type.systemBars());v.setPadding(bars.left,bars.top,bars.right,bars.bottom);return insets;});
        Switch master=new Switch(this);master.setText("Enable Battery Saving");master.setChecked(profile.optBoolean("enabled"));master.setEnabled(ready&&!busy);page.addView(master);
        text("Turning this off restores the values saved before this feature changed them. Changes below take effect after Save and apply.",13);
        master.setOnCheckedChangeListener((b,on)->{
            try{profile.put("enabled",on);save();}catch(Exception ignored){}
            if(on && needsConfirmation()) confirmProfile(()->run("apply",profile),()->{try{profile.put("enabled",false);save();}catch(Exception ignored){}draw();});
            else run("apply",profile);
        });
        text(status,14);
        button("Refresh device status",true,v->run("probe",null));
        card();text("Preset: "+profile.optString("preset"),18);
        text("Balanced preserves kernel and memory defaults and reports freezer state. Efficiency selects an advertised CPU ceiling near 85% of the original fastest-cluster limit; battery benefit needs measurement. Refresh-rate limiting stays optional. NOSLEN reproduces the module's intended settings. Stingy uses the lowest usable clocks and refresh rate, restricts downloaded and launchable preinstalled apps, disables auto-sync, dims the screen, removes animations and enables dark mode. Severe performance and background-function loss are intentional.",13);
        for(String name:new String[]{"Balanced","Efficiency","NOSLEN","Stingy"}) button(name,ready,v->{
            Runnable select=()->{try{
                boolean enabled=profile.getBoolean("enabled");
                JSONArray baseline=new JSONArray(caps.toString());
                for(int i=0;i<baseline.length();i++){JSONObject d=baseline.getJSONObject(i);if(d.has("original"))d.put("current",d.getString("original"));}
                profile=Profile.preset(name,baseline);profile.put("enabled",enabled);save();status="Preset selected. Review the controls, then Save and apply.";draw();
            }catch(Exception e){error(e);}};
            if(name.equals("Stingy"))confirmStingy(select,()->{});else if(name.equals("NOSLEN")) confirmNoslen(select,()->{});else select.run();
        });
        page=content;text("Controls",20);
        for(int i=0;i<caps.length();i++)try{control(caps.getJSONObject(i));}catch(Exception e){text(e.getMessage(),13);}
        card();text("Targeted app restrictions",18);
        text("Only select apps whose background work you can limit. Restricting messaging, alarms, VPN, wearables or Samsung feature providers can break expected behavior. No apps are selected by Balanced or Efficiency.",13);
        if("restricted".equals(profile.optJSONObject("controls").optString("background")))text("Aggressive restrictions automatically cover downloaded apps, launchable preinstalled apps and app stores when applied. The manual list below adds standby restrictions; it is not an exception list.",13);
        appButton("packages","Choose apps to restrict");
        discrete("Standby bucket",new String[]{"10","20","30","40","45"},new String[]{"Active","Working set","Frequent","Rare","Restricted"},profile.optString("bucket","45"),value->{try{profile.put("bucket",value);custom();}catch(Exception ignored){}});
        card();text("Doze exemptions",18);
        text("Remove only unnecessary user-added exemptions. Essential messaging and service dependencies should retain them. System exemptions may be protected by Android.",13);
        appButton("exemptions","Choose exemptions to remove");
        button("Save and apply",ready,v->{if(profile.optBoolean("enabled")&&needsConfirmation())confirmProfile(()->run("apply",profile),()->{});else run("apply",profile);});
        card();text("Profile backup",18);
        text("Backups contain your choices, not device paths or original system values. Restore validates the file and leaves Battery Saving off until you enable it.",13);
        button("Back up profile",true,v->{save();Intent it=new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("application/json").addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE,"UN1CA-Battery-"+profile.optString("preset")+".json");startActivityForResult(it,EXPORT);});
        button("Restore profile",ready,v->startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("application/json").addCategory(Intent.CATEGORY_OPENABLE),IMPORT));
        applyBackground();
    }
    private boolean needsConfirmation(){return "NOSLEN".equals(profile.optString("preset")) || "Stingy".equals(profile.optString("preset")) || "restricted".equals(profile.optJSONObject("controls").optString("background"));}
    private void confirmProfile(Runnable yes,Runnable no){if("NOSLEN".equals(profile.optString("preset")))confirmNoslen(yes,no);else confirmStingy(yes,no);}
    private void confirmStingy(Runnable yes,Runnable no){new AlertDialog.Builder(this).setTitle("Stingy: aggressive saving").setMessage("This profile deliberately sacrifices performance and background functions. Notifications, background sync, uploads, backups, app-store updates, widgets, VPNs and wearables can stop working. The screen becomes dim with a short timeout; CPU/GPU speed is heavily capped. Aggressive PCIe saving may expose driver instability. Core system services, launcher, keyboards and dialer remain protected. Android exemptions and foreground services mean zero background activity is not guaranteed. Disable Battery Saving to restore saved settings; missed work and notifications cannot be recovered. Select the preset, then Save and apply.").setPositiveButton("Continue",(d,w)->yes.run()).setNegativeButton("Cancel",(d,w)->no.run()).setOnCancelListener(d->no.run()).show();}
    private void confirmNoslen(Runnable yes,Runnable no){new AlertDialog.Builder(this).setTitle("NOSLEN profile").setMessage("NOSLEN disables Wi-Fi runtime suspend and PCIe link power saving, disables Wi-Fi wakeup, sets swappiness to 60 and cached processes to 32, and restricts three Samsung apps. It may increase drain or delay background communication. Unsupported controls will be reported.").setPositiveButton("Continue",(d,w)->yes.run()).setNegativeButton("Cancel",(d,w)->no.run()).setOnCancelListener(d->no.run()).show();}
    interface Choice {void set(String value);}
    private void discrete(String title,String[] values,String[] labels,String selected,Choice change){
        int index=0;for(int i=0;i<values.length;i++)if(values[i].equals(selected))index=i;
        TextView value=new TextView(this);value.setText(title+": "+labels[index]);page.addView(value);
        View bar=romSlider(title,values,labels,index,value,change);
        bar.setEnabled(ready&&!busy&&values.length>1);bar.setContentDescription(title);
        LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(-1,dp(56));
        params.topMargin=dp(8);page.addView(bar,params);
    }
    private View romSlider(String title,String[] values,String[] labels,int index,TextView value,Choice change){
        // Use Settings' bundled Samsung widget without shipping a duplicate AndroidX library.
        try{
            Class<?> type=Class.forName("androidx.appcompat.widget.SeslSeekBar");
            View bar=(View)type.getConstructor(Context.class).newInstance(this);
            type.getMethod("setMax",int.class).invoke(bar,values.length-1);
            type.getMethod("setProgress",int.class).invoke(bar,index);
            Class<?> listener=Class.forName("androidx.appcompat.widget.SeslSeekBar$OnSeekBarChangeListener");
            Object callback=java.lang.reflect.Proxy.newProxyInstance(listener.getClassLoader(),new Class<?>[]{listener},(proxy,method,args)->{
                String name=method.getName();
                if(name.equals("hashCode"))return System.identityHashCode(proxy);
                if(name.equals("equals"))return proxy==args[0];
                if(name.equals("toString"))return "BatterySliderListener";
                if(name.equals("onProgressChanged")){
                    int progress=(Integer)args[1];
                    value.setText(title+": "+labels[progress]);
                    // Includes keyboard/accessibility changes, which have no touch-stop event.
                    if((Boolean)args[2])change.set(values[progress]);
                }
                return null;
            });
            type.getMethod("setOnSeekBarChangeListener",listener).invoke(bar,callback);
            return bar;
        }catch(ReflectiveOperationException e){
            android.util.Log.w("UnicaBattery","Samsung slider unavailable; using platform widget",e);
            SeekBar bar=new SeekBar(this);bar.setMax(values.length-1);bar.setProgress(index);
            bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
                public void onStartTrackingTouch(SeekBar b){}
                public void onStopTrackingTouch(SeekBar b){}
                public void onProgressChanged(SeekBar b,int p,boolean user){value.setText(title+": "+labels[p]);if(user)change.set(values[p]);}
            });
            return bar;
        }
    }
    private String label(String id,String v){
        if(id.equals("background") && (v.equals("restricted")||v.equals("unrestricted")))return v.equals("restricted")?"Restrict downloaded and launchable preinstalled apps":"No automatic app restrictions";
        if(id.equals("night_mode")){if(v.equals("0"))return "Automatic";if(v.equals("1"))return "Light";if(v.equals("2"))return "Dark";if(v.equals("3"))return "Custom schedule";}
        if(id.equals("timeout") && !v.equals("null") && !v.equals("Unavailable") && !v.equals("__original"))return (Integer.parseInt(v)/1000)+" seconds";
        if(Arrays.asList("power_saver","sync","data_saver","brightness_mode","aod","wifi_scan","ble_scan","mobile_always").contains(id) && (v.equals("0")||v.equals("1")))return v.equals("1")?"On":"Off";
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
        card();String id=d.getString("id");text(d.getString("label"),18);text(d.getString("help"),13);
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
