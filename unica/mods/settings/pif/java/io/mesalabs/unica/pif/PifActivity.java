/* Copyright (c) 2026 Rezoss
 * SPDX-License-Identifier: GPL-3.0-or-later */
package io.mesalabs.unica.pif;

import android.app.*;
import android.os.*;
import android.content.*;
import android.view.*;
import android.widget.*;
import org.json.*;
import java.io.File;
import java.util.*;
import java.util.concurrent.*;

/** Runs inside platform Settings. Profile application uses its existing property permissions. */
public final class PifActivity extends Activity {
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final List<View> cards=new ArrayList<>();
    private LinearLayout content,card;
    private LinkedHashMap<String,String> draft;
    private boolean busy;
    private String status="Changes are drafts until Save and apply. Applying restarts Play services and Play Store.";
    private String source="Custom";
    private int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
    private static String property(String key,String fallback)throws Exception {
        return (String)Class.forName("android.os.SemSystemProperties").getMethod("get",String.class,String.class).invoke(null,key,fallback);
    }
    private static void propertySet(String key,String value)throws Exception {
        Class.forName("android.os.SemSystemProperties").getMethod("set",String.class,String.class).invoke(null,key,value);
    }
    private LinkedHashMap<String,String> current()throws Exception {
        LinkedHashMap<String,String> values=new LinkedHashMap<>();
        for(String key:Profile.KEYS.keySet()) {
            String fallback="null";
            if(key.equals("FINGERPRINT"))fallback=Defaults.FINGERPRINT;
            if(key.equals("SECURITY_PATCH"))fallback=Defaults.SECURITY_PATCH;
            if(key.equals("DEVICE_INITIAL_SDK_INT"))fallback=Defaults.FIRST_API;
            if(Profile.flag(key))fallback=key.equals("spoofVendingBuild")?"false":"true";
            String value=property("persist.sys.pif."+Profile.KEYS.get(key),fallback);
            if(Profile.flag(key))value=Boolean.toString(value.equals("true")||value.equals("1"));
            values.put(key,value);
        }
        return values;
    }
    private boolean supported(){return Build.VERSION.SDK_INT==37&&new File("/system/lib64/libunica_pif.so").isFile();}
    @Override public void onCreate(Bundle state){
        super.onCreate(state);setTitle("Play Integrity Fix");
        if(getActionBar()!=null)getActionBar().setDisplayHomeAsUpEnabled(true);
        try {
            draft=current();
            if(state!=null){draft=Profile.parse(state.getString("draft",Profile.format(draft)),false);source=state.getString("source","Custom");}
            draw();
        }catch(Exception e){new AlertDialog.Builder(this).setMessage(message(e)).setPositiveButton("Close",(d,w)->finish()).setOnCancelListener(d->finish()).show();}
    }
    @Override protected void onSaveInstanceState(Bundle state){super.onSaveInstanceState(state);if(draft!=null){state.putString("draft",Profile.format(draft));state.putString("source",source);}}
    @Override protected void onDestroy(){worker.shutdown();super.onDestroy();}
    @Override public boolean onOptionsItemSelected(MenuItem item){if(item.getItemId()==android.R.id.home){onBackPressed();return true;}return super.onOptionsItemSelected(item);}
    @Override public void onBackPressed(){if(busy){Toast.makeText(this,"Wait for the profile operation to finish",Toast.LENGTH_SHORT).show();return;}super.onBackPressed();}
    private String message(Exception e){Throwable cause=e;while(cause.getCause()!=null)cause=cause.getCause();return cause.getMessage()==null?cause.toString():cause.getMessage();}
    private void error(Exception e){status="Failed: "+message(e);draw();}
    private void group(){card=new LinearLayout(this);card.setOrientation(1);card.setPadding(dp(20),dp(12),dp(20),dp(16));LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.bottomMargin=dp(16);content.addView(card,p);cards.add(card);}
    private void text(String value,int size){TextView v=new TextView(this);v.setText(value);v.setTextSize(size);v.setPadding(0,dp(8),0,dp(8));v.setTextIsSelectable(true);card.addView(v);}
    private void button(String label,Runnable action){Button b=new Button(this);b.setText(label);b.setAllCaps(false);b.setMinHeight(dp(48));b.setEnabled(!busy);b.setOnClickListener(v->action.run());card.addView(b,new LinearLayout.LayoutParams(-1,-2));}
    private void toggle(String title,String key){Switch v=new Switch(this);v.setText(title);v.setPadding(0,dp(12),0,dp(12));v.setChecked("true".equals(draft.get(key))||"1".equals(draft.get(key)));v.setEnabled(!busy&&supported());v.setOnCheckedChangeListener((b,on)->{draft.put(key,Boolean.toString(on));source="Custom";});card.addView(v);}
    private void unavailable(String title){Switch v=new Switch(this);v.setText(title+"\nUnavailable in this ROM backend");v.setChecked(false);v.setEnabled(false);v.setPadding(0,dp(12),0,dp(12));card.addView(v);}
    private void draw(){
        if(isFinishing()||isDestroyed())return;
        cards.clear();ScrollView scroll=new ScrollView(this);content=new LinearLayout(this);content.setOrientation(1);content.setPadding(dp(12),dp(12),dp(12),dp(24));scroll.addView(content);setContentView(scroll);
        scroll.setOnApplyWindowInsetsListener((v,insets)->{android.graphics.Insets bars=insets.getInsets(WindowInsets.Type.systemBars());v.setPadding(bars.left,bars.top,bars.right,bars.bottom);return insets;});
        group();text("Current configured profile",20);
        try {
            Map<String,String> live=current();
            String[] identity=live.get("FINGERPRINT").split("[/:]",-1);
            String device=live.get("DEVICE"),model=live.get("MODEL");
            if((device.equals("null")||device.isEmpty())&&identity.length==8)device=identity[2]+" (automatic)";
            if(model.equals("null")||model.isEmpty())model=identity.length==8&&identity[2].equals("bluejay")?"Pixel 6a (automatic)":"Not overridden";
            text("PIF: "+property("persist.sys.unica.pif","true")+"\nProfile: "+property("persist.sys.pif.version","ROM default")+"\nModel: "+model+"\nDevice: "+device+"\nSecurity patch: "+live.get("SECURITY_PATCH"),16);
            text(live.get("FINGERPRINT"),14);
        }catch(Exception e){text(message(e),14);}
        text("These are configured values. A null identity field is derived from the fingerprint by the native backend. Runtime activation and Play Integrity verdicts require device testing.",14);
        button("View current configuration",()->{try{showText("Current configured properties",Profile.format(current()));}catch(Exception e){error(e);}});
        group();text("Pixel profile",20);text("Draft: "+source,16);text(status,14);
        if(!supported())text("Native configuration controls require the dm3q SDK 37 PIF backend.",14);
        button(busy?"Working…":"Choose / fetch Pixel profile",this::devices);
        button("Edit / add PIF properties",this::edit);
        group();text("Spoof configuration",20);
        toggle("Spoof Build","spoofBuild");toggle("Spoof Build · Play Store","spoofVendingBuild");toggle("Spoof Props","spoofProps");
        text("Spoof Build also gates the optional Play Store Build override. Play Store is excluded while its switch is off.",14);
        unavailable("Spoof Provider");unavailable("Spoof Signature");unavailable("Spoof SDK · Play Store");
        text("Provider, signature and SDK replacement are not implemented by the ROM PIF backend. Existing keybox handling is preserved.",14);
        group();button("View draft",()->showText("Draft properties",Profile.format(draft)));
        button("Save and apply",()->{if(!supported()){status="Native PIF backend unavailable";draw();return;}try{Profile.validate(draft);new AlertDialog.Builder(this).setTitle("Apply PIF profile?").setMessage("This replaces the configured profile and restarts Play services and Play Store. A rollback snapshot will be saved. Integrity results are not guaranteed.").setPositiveButton("Apply",(d,w)->apply(draft)).setNegativeButton("Cancel",null).show();}catch(Exception e){error(e);}});
        button("Restore previous configuration",this::restore);
        button("Discard draft / refresh",()->{try{draft=current();source="Current configuration";status="Draft refreshed from configured properties.";draw();}catch(Exception e){error(e);}});
        style();
    }
    private void style(){
        boolean dark=(getResources().getConfiguration().uiMode&0x30)==0x20;
        boolean transparent=android.provider.Settings.System.getInt(getContentResolver(),"unica_settings_bg_transparency_enabled",0)!=0;
        int level=Math.max(70,Math.min(100,android.provider.Settings.System.getInt(getContentResolver(),"unica_settings_bg_transparency_level",70)));
        int surface=color("sesl_background_color_"+(dark?"dark":"light"),dark?0xff17171a:0xffffffff);
        int background=color("sesl_round_and_bgcolor_"+(dark?"dark":"light"),dark?0xff000000:0xfff2f2f2);
        if(transparent){surface=(surface&0xffffff)|((255-level*255/100)<<24);background=0;getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER);}else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER);
        getWindow().setFormat(transparent?android.graphics.PixelFormat.TRANSLUCENT:android.graphics.PixelFormat.OPAQUE);
        getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(background));
        for(View v:cards){android.graphics.drawable.GradientDrawable shape=new android.graphics.drawable.GradientDrawable();shape.setColor(surface);shape.setCornerRadius(dp(28));v.setBackground(shape);}
    }
    private int color(String name,int fallback){int id=getResources().getIdentifier(name,"color",getPackageName());return id==0?fallback:getColor(id);}
    private void showText(String title,String text){TextView v=new TextView(this);v.setText(text);v.setTextIsSelectable(true);v.setPadding(dp(20),dp(12),dp(20),dp(12));ScrollView scroll=new ScrollView(this);scroll.addView(v);new AlertDialog.Builder(this).setTitle(title).setView(scroll).setPositiveButton("Close",null).show();}
    private void edit(){
        EditText editor=new EditText(this);editor.setText(Profile.format(draft));editor.setGravity(Gravity.TOP);editor.setMinLines(8);editor.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE|android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        ScrollView scroll=new ScrollView(this);scroll.addView(editor);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("Edit PIF properties").setMessage("KEY=value, one per line. Supported keys only. Missing identity fields become automatic (null). Unknown keys are rejected.").setView(scroll).setPositiveButton("Save draft",null).setNegativeButton("Cancel",null).create();
        dialog.setOnShowListener(d->dialog.getButton(-1).setOnClickListener(v->{try{draft=Profile.replacement(Profile.parse(editor.getText().toString(),false),draft);source="Custom";status="Draft updated. Review before applying.";dialog.dismiss();draw();}catch(Exception e){editor.setError(message(e));}}));dialog.show();
    }
    private interface Job {Object run()throws Exception;}
    private interface Done {void run(Object value)throws Exception;}
    private void background(Job job,Done done){
        busy=true;draw();worker.submit(()->{try{Object result=job.run();runOnUiThread(()->{busy=false;if(isDestroyed())return;try{done.run(result);}catch(Exception e){error(e);}});}catch(Exception e){runOnUiThread(()->{busy=false;if(!isDestroyed())error(e);});}});
    }
    private void devices(){
        background(()->new JSONArray(PixelSource.get("device_list.json")),value->{
            JSONArray list=(JSONArray)value;List<String> labels=new ArrayList<>(),products=new ArrayList<>();
            labels.add("Random");products.add("");
            for(int i=0;i<list.length();i++){JSONObject item=list.getJSONObject(i);String model=item.getString("model"),product=item.getString("product");if(!model.startsWith("Pixel ")||!product.matches("[a-z0-9_]+"))continue;labels.add(model);products.add(product);}
            if(products.size()<2)throw new IllegalArgumentException("No Pixel profiles available");
            draw();final int[] selected={0};
            new AlertDialog.Builder(this).setTitle("Select Pixel device").setSingleChoiceItems(labels.toArray(new String[0]),0,(d,n)->selected[0]=n).setNegativeButton("Cancel",null).setPositiveButton("Fetch",(d,w)->{
                int index=selected[0]==0?1+new java.security.SecureRandom().nextInt(products.size()-1):selected[0];
                background(()->Profile.parse(PixelSource.get("device_prop/"+products.get(index)+".prop"),true),profile->{
                    @SuppressWarnings("unchecked") Map<String,String> fetched=(Map<String,String>)profile;
                    draft=Profile.replacement(fetched,draft);source=labels.get(index)+" · KOWX712 GitHub";status="Fetched into draft. Review the profile and Save and apply.";draw();
                });
            }).show();
        });
    }
    private JSONObject snapshot()throws Exception {
        JSONObject saved=new JSONObject();
        Map<String,String> configured=current();
        for(String key:Profile.KEYS.keySet())saved.put("persist.sys.pif."+Profile.KEYS.get(key),configured.get(key));
        saved.put("persist.sys.pif.version",property("persist.sys.pif.version",""));
        return saved;
    }
    private void writeSnapshot(JSONObject saved)throws Exception {Iterator<String> keys=saved.keys();while(keys.hasNext()){String key=keys.next();if(!key.equals("persist.sys.pif.version")&&!Profile.KEYS.containsValue(key.replace("persist.sys.pif.","")))throw new IllegalArgumentException("Invalid backup key");propertySet(key,saved.getString(key));}}
    private void apply(Map<String,String> requested){
        final Map<String,String> values=new LinkedHashMap<>(requested);final String label=source;
        background(()->{
            Profile.validate(values);JSONObject previous=snapshot();String master=property("persist.sys.unica.pif","true");
            if(!getPreferences(0).edit().putString("rollback",previous.toString()).commit())throw new IllegalStateException("Could not save rollback snapshot");
            try{
                propertySet("persist.sys.unica.pif","false");
                for(String key:Profile.KEYS.keySet())propertySet("persist.sys.pif."+Profile.KEYS.get(key),values.getOrDefault(key,"null"));
                propertySet("persist.sys.pif.version",label.length()>80?label.substring(0,80):label);
                propertySet("persist.sys.unica.pif",master);
            }catch(Exception e){try{writeSnapshot(previous);}finally{propertySet("persist.sys.unica.pif",master);}throw e;}
            return restart();
        },result->{draft=current();source="Current configuration";status=(String)result;draw();});
    }
    private String restart(){
        try{Class<?> utils=Class.forName("io.mesalabs.unica.settings.pif.PIFUtils");utils.getMethod("killGMS",android.content.Context.class).invoke(null,this);return "Configuration saved. Play services and Play Store stopped; they use the profile on next start.";}
        catch(Exception e){return "Configuration saved. Restart failed: "+message(e)+". Reboot to activate the new profile.";}
    }
    private void restore(){
        String raw=getPreferences(0).getString("rollback",null);if(raw==null){status="No previous configuration saved yet.";draw();return;}
        new AlertDialog.Builder(this).setTitle("Restore previous profile?").setMessage("Restores the saved properties and restarts Play services and Play Store.").setNegativeButton("Cancel",null).setPositiveButton("Restore",(d,w)->background(()->{
            JSONObject saved=new JSONObject(raw),previous=snapshot();String master=property("persist.sys.unica.pif","true");
            try{propertySet("persist.sys.unica.pif","false");writeSnapshot(saved);propertySet("persist.sys.unica.pif",master);}
            catch(Exception e){try{writeSnapshot(previous);}finally{propertySet("persist.sys.unica.pif",master);}throw e;}
            return restart();
        },result->{draft=current();source="Restored configuration";status=(String)result;draw();})).show();
    }
}
