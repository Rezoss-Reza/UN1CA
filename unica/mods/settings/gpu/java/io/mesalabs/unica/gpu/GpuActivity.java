/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.mesalabs.unica.gpu;

import android.app.*;
import android.content.*;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.math.BigDecimal;
import java.security.MessageDigest;
import java.util.concurrent.*;
import org.json.*;

/** ROM-native image editor. No root manager, automatic profile or boot writes. */
public final class GpuActivity extends Activity {
    private static final int EXPORT=51,PROFILE_EXPORT=52,PROFILE_IMPORT=53;
    private static final int[] LEVELS={52,56,60,64,80,128,144,192,224};
    private static final String[] LABELS={"52 · LOW_SVS_D2","56 · LOW_SVS_D1","60 · LOW_SVS_D0","64 · LOW_SVS","80 · LOW_SVS_L1","128 · SVS","144 · SVS_L0","192 · SVS_L1","224 · SVS_L2"};
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final long[] draftFrequency=new long[10];
    private final int[] draftLevel=new int[10];
    private boolean hasDraft,invalidSavedProfile;
    private String profileName="My GPU profile",promptedImage="";
    private final java.util.List<LinearLayout> cards=new java.util.ArrayList<>();
    private LinearLayout page;
    private ScrollView scroller;
    private JSONObject state;
    private boolean busy;
    private String message="Checking firmware compatibility…";
    private LinearLayout content;
    private int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
    @Override public void onCreate(Bundle b){super.onCreate(b);setTitle("Advanced GPU Tuning (Konabess)");if(getActionBar()!=null)getActionBar().hide();loadSavedProfile();draw();run("status","");}
    @Override public boolean onOptionsItemSelected(MenuItem item){if(item.getItemId()==android.R.id.home){onBackPressed();return true;}return super.onOptionsItemSelected(item);}
    @Override public void onBackPressed(){if(busy){Toast.makeText(this,"Wait for the GPU operation to finish",Toast.LENGTH_SHORT).show();return;}super.onBackPressed();}
    @Override protected void onDestroy(){worker.shutdown();super.onDestroy();}
    @Override protected void onResume(){super.onResume();applyBackground();}
    private int themeColor(int attr,int fallback){
        android.content.res.TypedArray a=obtainStyledAttributes(new int[]{attr});
        int color=a.getColor(0,fallback);a.recycle();return color;
    }
    private int romColor(String name,int fallback){int id=getResources().getIdentifier(name,"color",getPackageName());return id==0?fallback:getColor(id);}
    private void applyBackground(){
        boolean dark=(getResources().getConfiguration().uiMode&0x30)==0x20;
        boolean transparent=android.provider.Settings.System.getInt(getContentResolver(),"unica_settings_bg_transparency_enabled",0)!=0;
        int amount=Math.max(70,Math.min(100,android.provider.Settings.System.getInt(getContentResolver(),"unica_settings_bg_transparency_level",70)));
        int surface=romColor("sesl_background_color_"+(dark?"dark":"light"),dark?0xff17171a:0xffffffff);
        int background=romColor("sesl_round_and_bgcolor_"+(dark?"dark":"light"),dark?0xff000000:0xfff2f2f2);
        if(transparent){surface=(surface&0xffffff)|((255-amount*255/100)<<24);background=android.graphics.Color.TRANSPARENT;getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER);}
        else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER);
        getWindow().setFormat(transparent?android.graphics.PixelFormat.TRANSLUCENT:android.graphics.PixelFormat.OPAQUE);
        getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(background));
        for(LinearLayout card:cards){android.graphics.drawable.GradientDrawable shape=new android.graphics.drawable.GradientDrawable();shape.setColor(surface);shape.setCornerRadius(dp(28));card.setBackground(shape);}
    }
    private TextView label(String value,int size,boolean secondary){
        TextView v=new TextView(this);v.setText(value);v.setTextSize(size);v.setIncludeFontPadding(true);
        v.setTextColor(themeColor(secondary?android.R.attr.textColorSecondary:android.R.attr.textColorPrimary,secondary?0xff999999:0xffeeeeee));
        v.setPadding(0,dp(2),0,dp(2));return v;
    }
    private void section(String title){TextView v=label(title,14,true);v.setPadding(dp(24),dp(14),dp(24),dp(8));v.setAccessibilityHeading(true);content.addView(v);}
    private void card(){
        page=new LinearLayout(this);page.setOrientation(LinearLayout.VERTICAL);page.setPadding(0,dp(4),0,dp(4));page.setClipToOutline(true);
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.bottomMargin=dp(16);content.addView(page,p);cards.add(page);
    }
    private void description(String title,String summary){
        LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);box.setPadding(dp(24),dp(16),dp(24),dp(16));
        TextView name=label(title,20,false);name.setTypeface(null,android.graphics.Typeface.BOLD);box.addView(name);
        if(summary!=null){TextView detail=label(summary,14,true);LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.topMargin=dp(4);box.addView(detail,p);}page.addView(box);
    }
    private void divider(){
        if(page.getChildCount()==0)return;View v=new View(this);int color=themeColor(android.R.attr.textColorSecondary,0xff888888);v.setBackgroundColor((color&0xffffff)|0x26000000);
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,dp(1));p.setMargins(dp(24),0,dp(24),0);page.addView(v,p);
    }
    private void row(String title,String summary,boolean enabled,boolean action,View.OnClickListener listener){
        divider();LinearLayout line=new LinearLayout(this);line.setGravity(Gravity.CENTER_VERTICAL);line.setMinimumHeight(dp(76));line.setPadding(dp(24),dp(14),dp(20),dp(14));
        LinearLayout words=new LinearLayout(this);words.setOrientation(LinearLayout.VERTICAL);TextView name=label(title,18,false);
        if(action)name.setTextColor(themeColor(android.R.attr.colorAccent,0xff8ab4f8));words.addView(name);
        if(summary!=null){TextView detail=label(summary,14,true);LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.topMargin=dp(3);words.addView(detail,p);}
        line.addView(words,new LinearLayout.LayoutParams(0,-2,1));TextView arrow=label("›",26,true);arrow.setPadding(dp(12),0,0,0);arrow.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);line.addView(arrow,new LinearLayout.LayoutParams(-2,-2));
        boolean active=enabled&&!busy;line.setEnabled(active);line.setAlpha(active?1f:0.45f);line.setFocusable(active);line.setClickable(active);
        android.util.TypedValue ripple=new android.util.TypedValue();if(getTheme().resolveAttribute(android.R.attr.selectableItemBackground,ripple,true))line.setBackgroundResource(ripple.resourceId);
        line.setOnClickListener(v->{if(active)listener.onClick(v);});line.setContentDescription(title+(summary==null?"":", "+summary));page.addView(line,new LinearLayout.LayoutParams(-1,-2));
    }
    private void button(String title,boolean enabled,View.OnClickListener listener){row(title,null,enabled,true,listener);}
    private boolean ready(){return state!=null&&!busy;}
    private String hash(){return state==null?"":state.optString("currentHash");}
    private String mhz(long hz){return BigDecimal.valueOf(hz).movePointLeft(6).stripTrailingZeros().toPlainString();}
    private String levelName(int value){for(int i=0;i<LEVELS.length;i++)if(LEVELS[i]==value)return LABELS[i];return Integer.toString(value);}
    private SharedPreferences profileStore(){return createDeviceProtectedStorageContext().getSharedPreferences("unica_gpu_profile",MODE_PRIVATE);}
    private GpuProfile profile()throws Exception {return GpuProfile.create(profileName,android.os.Build.FINGERPRINT,draftFrequency,draftLevel);}
    private void setProfile(GpuProfile p,boolean persist)throws Exception {
        if(persist&&!profileStore().edit().putString("profile",new String(p.encode(),java.nio.charset.StandardCharsets.UTF_8)).commit())throw new IOException("Cannot save tuning profile");
        System.arraycopy(p.frequencies,0,draftFrequency,0,10);System.arraycopy(p.levels,0,draftLevel,0,10);profileName=p.name;hasDraft=true;invalidSavedProfile=false;
    }
    private void loadSavedProfile(){
        String saved=profileStore().getString("profile",null);if(saved==null)return;
        try{setProfile(GpuProfile.parse(saved.getBytes(java.nio.charset.StandardCharsets.UTF_8)),false);}catch(Exception e){invalidSavedProfile=true;alert("Saved profile unavailable","The saved data was kept for recovery. "+e.getMessage());}
    }
    private void syncDraft(){
        if(state==null||hasDraft||invalidSavedProfile)return;
        try{setProfile(imageProfile(),true);}catch(Exception e){message="Cannot initialize tuning profile: "+e.getMessage();}
    }
    private GpuProfile imageProfile()throws Exception {
        long[] f=new long[10];int[] l=new int[10];JSONArray fs=state.getJSONArray("frequencies"),ls=state.getJSONArray("levels");for(int i=0;i<10;i++){f[i]=fs.getLong(i);l[i]=ls.getInt(i);}return GpuProfile.create("My GPU profile",android.os.Build.FINGERPRINT,f,l);
    }
    private void saveDraft(long[] f,int[] l)throws Exception {setProfile(GpuProfile.create(profileName,android.os.Build.FINGERPRINT,f,l),true);}
    private void chooseProfile(int request){
        Intent i=new Intent(request==PROFILE_EXPORT?Intent.ACTION_CREATE_DOCUMENT:Intent.ACTION_OPEN_DOCUMENT);i.addCategory(Intent.CATEGORY_OPENABLE);i.setType(request==PROFILE_EXPORT?"application/json":"*/*");
        if(request==PROFILE_EXPORT)i.putExtra(Intent.EXTRA_TITLE,"UN1CA-dm3q-GPU-profile.json");
        try{startActivityForResult(i,request);}catch(ActivityNotFoundException e){alert("File picker unavailable",e.getMessage());}
    }
    private void createBackup(){
        if(!ready())return;
        if(state.optBoolean("canRenew")){
            String current=hash(),baseline=state.optString("baselineHash");
            new AlertDialog.Builder(this).setTitle(state.optBoolean("renewing")?"Resume backup refresh?":"Create backup for current ROM?").setMessage("The current image differs from the saved original. Keep the old rollback archived, back up this ROM's clean vendor_boot, then save the new rollback ZIP. Your tuning profile stays saved. This does not flash either image.").setPositiveButton("Create backup",(d,w)->run("renew",current+" "+baseline)).setNegativeButton("Cancel",null).show();
        }else run("backup",hash());
    }
    private void promptBackup(){
        if(!ready()||!state.optBoolean("canRenew")||hash().equals(promptedImage))return;promptedImage=hash();createBackup();
    }
    private void profileDocument(int request,Uri uri){
        if(busy)return;final byte[] outgoing;
        try{outgoing=request==PROFILE_EXPORT?profile().encode():null;}catch(Exception e){alert("Profile unavailable",e.getMessage());return;}
        busy=true;message=request==PROFILE_EXPORT?"Saving tuning profile…":"Reading tuning profile…";draw();
        worker.execute(()->{GpuProfile imported=null;String error=null;try{
            if(outgoing!=null){
                try(OutputStream out=getContentResolver().openOutputStream(uri,"wt")){if(out==null)throw new IOException("Cannot open destination");out.write(outgoing);}
                try(InputStream in=getContentResolver().openInputStream(uri)){if(!java.util.Arrays.equals(outgoing,readProfile(in)))throw new IOException("Saved profile verification failed");}
            }else try(InputStream in=getContentResolver().openInputStream(uri)){imported=GpuProfile.parse(readProfile(in));}
        }catch(Exception e){error=e.getMessage();}final GpuProfile loaded=imported;final String fail=error;
        runOnUiThread(()->{if(isDestroyed())return;busy=false;if(fail!=null){message="Profile operation failed: "+fail;alert("Profile operation failed",message);}else if(loaded!=null){message="Profile validated. Review before loading.";reviewProfile(loaded);}else message="Tuning profile saved and verified. Share this JSON file; keep your recovery rollback ZIP separately.";draw();});});
    }
    private static byte[] readProfile(InputStream in)throws Exception {
        if(in==null)throw new IOException("Cannot read profile");ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] bytes=new byte[4096];int n;while((n=in.read(bytes))!=-1){if(out.size()+n>GpuProfile.MAX_BYTES)throw new IOException("Profile exceeds 32 KiB");out.write(bytes,0,n);}return out.toByteArray();
    }
    private void reviewProfile(GpuProfile p){
        StringBuilder review=new StringBuilder(p.name).append("\nS23 Ultra · Adreno 740 v2\n\n");
        for(int i=0;i<10;i++){review.append("Level ").append(i).append(": ");if(hasDraft)review.append(mhz(draftFrequency[i])).append(" → ");review.append(mhz(p.frequencies[i])).append(" MHz; RPMh ");if(hasDraft)review.append(draftLevel[i]).append(" → ");review.append(p.levels[i]).append('\n');}
        review.append("\nLoading saves these values only. Apply still requires compatible firmware, a freshly exported rollback and your confirmation. A shared profile may be unstable on your individual chip.");
        new AlertDialog.Builder(this).setTitle("Load imported tuning profile?").setMessage(review).setPositiveButton("Load profile",(d,w)->{try{setProfile(p,true);message="Imported profile saved. Review the values before Apply.";draw();}catch(Exception e){alert("Cannot save profile",e.getMessage());}}).setNegativeButton("Cancel",null).show();
    }
    private void editFrequency(int index){
        if(!ready()||state.optBoolean("interrupted"))return;
        LinearLayout body=new LinearLayout(this);body.setOrientation(LinearLayout.VERTICAL);body.setPadding(dp(24),dp(12),dp(24),dp(8));
        body.addView(label("GPU frequency (MHz)",14,true));EditText input=new EditText(this);input.setSingleLine(true);input.setTextSize(20);input.setIncludeFontPadding(true);input.setMinHeight(dp(64));input.setPadding(dp(12),dp(16),dp(12),dp(16));
        input.setInputType(InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_FLAG_DECIMAL);input.setText(mhz(draftFrequency[index]));input.setSelectAllOnFocus(true);input.setContentDescription("Power level "+index+" GPU frequency in MHz");body.addView(input,new LinearLayout.LayoutParams(-1,-2));
        body.addView(label("124.8–850 MHz, in 0.1 MHz steps. Frequencies must descend down the table. This range does not guarantee stability.",14,true));
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("Power level "+index).setView(body).setPositiveButton("Done",null).setNegativeButton("Cancel",null).create();
        dialog.setOnShowListener(d->{dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{try{
            long value=new BigDecimal(input.getText().toString()).movePointRight(6).longValueExact();
            if(value<124800000||value>850000000||value%100000!=0||(index>0&&value>=draftFrequency[index-1])||(index<9&&value<=draftFrequency[index+1]))throw new IllegalArgumentException("Use a value between adjacent levels, in 0.1 MHz steps.");
            long[] f=draftFrequency.clone();f[index]=value;saveDraft(f,draftLevel);dialog.dismiss();draw();
        }catch(Exception e){input.setError(e.getMessage()==null?"Enter a valid frequency":e.getMessage());}});input.requestFocus();});
        dialog.show();dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
    }
    private void editLevel(int index){
        if(!ready()||state.optBoolean("interrupted"))return;int selected=0;for(int i=0;i<LEVELS.length;i++)if(LEVELS[i]==draftLevel[index])selected=i;
        new AlertDialog.Builder(this).setTitle("Power level "+index+" · Voltage level").setSingleChoiceItems(LABELS,selected,(d,which)->{
            int value=LEVELS[which];if((index>0&&value>draftLevel[index-1])||(index<9&&value<draftLevel[index+1])){Toast.makeText(this,"Voltage levels must stay unchanged or descend down the table",Toast.LENGTH_LONG).show();return;}
            try{int[] l=draftLevel.clone();l[index]=value;saveDraft(draftFrequency,l);d.dismiss();draw();}catch(Exception e){alert("Cannot save profile",e.getMessage());}
        }).setNegativeButton("Cancel",null).show();
    }
    private void draw(){
        int previousScroll=scroller==null?0:scroller.getScrollY();syncDraft();cards.clear();LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);
        getWindow().setDecorFitsSystemWindows(false);
        root.setOnApplyWindowInsetsListener((v,i)->{android.graphics.Insets bars=i.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout());v.setPadding(bars.left,bars.top,bars.right,bars.bottom);return i;});
        LinearLayout toolbar=new LinearLayout(this);toolbar.setGravity(Gravity.CENTER_VERTICAL);toolbar.setPadding(dp(8),dp(4),dp(20),dp(4));toolbar.setMinimumHeight(dp(56));
        TextView back=label("‹",36,false);back.setGravity(Gravity.CENTER);back.setMinHeight(dp(48));back.setContentDescription("Navigate up");back.setOnClickListener(v->onBackPressed());back.setFocusable(true);toolbar.addView(back,new LinearLayout.LayoutParams(dp(48),-2));
        TextView title=label("Advanced GPU Tuning",20,false);title.setTypeface(null,android.graphics.Typeface.BOLD);toolbar.addView(title,new LinearLayout.LayoutParams(0,-2,1));root.addView(toolbar);
        ScrollView scroll=new ScrollView(this);scroller=scroll;scroll.setFillViewport(true);scroll.setClipToPadding(false);content=new LinearLayout(this);content.setOrientation(LinearLayout.VERTICAL);content.setPadding(dp(12),dp(12),dp(12),dp(24));scroll.addView(content);
        root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));setContentView(root);
        card();description("KonaBess", "GPU frequency and voltage tuning for S23 Ultra.");
        description("Trial changes", "Changes take effect after reboot. Unsuitable values can cause freezes or prevent booting. Keep your recovery rollback available.");
        section("Device status");card();description(busy?"Working…":"GPU image",message);
        button("Refresh image and compatibility",true,v->run("status",""));
        section("Tuning profile");card();
        description(hasDraft?profileName:"No saved profile","Values are saved automatically and retained across ROM updates that preserve Settings data. Imported values are never applied automatically.");
        row("Back up profile","Export a shareable JSON file with frequencies and voltage levels",hasDraft,false,v->chooseProfile(PROFILE_EXPORT));
        row("Import profile","Validate and review a UN1CA GPU profile before loading",true,false,v->chooseProfile(PROFILE_IMPORT));
        row("Load current image values","Replace the saved draft with the current image's table",ready(),false,v->new AlertDialog.Builder(this).setTitle("Replace saved profile values?").setMessage("This replaces the saved draft. Back up your profile first if you want to keep it.").setPositiveButton("Load values",(d,w)->{try{setProfile(imageProfile(),true);draw();}catch(Exception e){alert("Cannot load values",e.getMessage());}}).setNegativeButton("Cancel",null).show());
        section("Recovery rollback");card();
        row(state!=null&&state.optBoolean("renewing")?"Resume backup refresh":state!=null&&state.optBoolean("staleBackup")?"Create backup for current ROM":"Create or verify original backup",state!=null&&state.optBoolean("staleBackup")?"Archive old rollback and back up this ROM; profile stays saved":state!=null&&state.optBoolean("backup")?"Original image saved":"Save the original before making changes",ready()&&(state.optBoolean("canRenew")||(!state.optBoolean("staleBackup")&&!state.optBoolean("renewing")&&!state.optBoolean("interrupted")&&(!state.optBoolean("backup")||state.optBoolean("original")))),false,v->createBackup());
        row("Save rollback ZIP",state!=null&&state.optBoolean("exported")?"Saved copy verified":"Required before Apply · choose PC or USB storage",ready()&&state.optBoolean("backup")&&state.optBoolean("canRestore")&&!state.optBoolean("interrupted")&&!state.optBoolean("renewing"),false,v->{Intent i=new Intent(Intent.ACTION_CREATE_DOCUMENT);i.addCategory(Intent.CATEGORY_OPENABLE);i.setType("application/zip");i.putExtra(Intent.EXTRA_TITLE,"UN1CA-dm3q-GPU-rollback.zip");try{startActivityForResult(i,EXPORT);}catch(ActivityNotFoundException e){alert("File picker unavailable",e.getMessage());}});
        row("Recovery instructions","Restore through OrangeFox if Android cannot start",true,false,v->alert("If Android cannot start","Boot into OrangeFox using the device's recovery procedure. Mount Cache. Flash /cache/unica-gpu/rollback.zip, or flash the saved rollback ZIP from USB/PC storage. It verifies and restores the exact pre-trial vendor_boot image. Reboot manually after success. Keep a matching full ROM available if recovery restoration fails. Never use this ZIP after changing firmware."));
        section("GPU power levels");card();
        row("About GPU tuning","MHz frequencies · Qualcomm RPMh voltage requests",true,false,v->alert("GPU power levels","Frequency is MHz (0.1 MHz steps, 124.8–850). This range is an input limit, not a promise of hardware stability. Levels are Qualcomm RPMh requests, not millivolts; the platform may round them up. Keep frequencies descending and levels unchanged or descending down the table. Start with one small change."));
        if(hasDraft){
            for(int index=0;index<10;index++){final int row=index;section("Power level "+row);card();
                row("GPU frequency",mhz(draftFrequency[row])+" MHz",ready()&&!state.optBoolean("interrupted")&&!state.optBoolean("renewing"),false,v->editFrequency(row));
                row("Voltage level",levelName(draftLevel[row]),ready()&&!state.optBoolean("interrupted")&&!state.optBoolean("renewing"),false,v->editLevel(row));
            }
        }
        card();button("Apply GPU table",ready()&&state.optBoolean("exported")&&state.optBoolean("canRestore")&&!state.optBoolean("interrupted"),v->apply());
        section("Restore and restart");card();
        row("Restore original vendor_boot","Restore the pre-trial image, then reboot",ready()&&state.optBoolean("canRestore"),false,v->new AlertDialog.Builder(this).setTitle("Restore original GPU image?").setMessage("This restores the full vendor_boot image captured before this trial, including its original GPU table. Reboot after restoration succeeds.").setPositiveButton("Restore",(d,w)->run("restore",hash())).setNegativeButton("Cancel",null).show());
        row("Reboot now","Only after Apply or Restore reports success",ready()&&!state.optBoolean("interrupted")&&!state.optBoolean("renewing"),false,v->new AlertDialog.Builder(this).setTitle("Reboot device?").setMessage("Keep the rollback ZIP and recovery available. Only reboot after Apply or Restore reports success.").setPositiveButton("Reboot",(d,w)->((android.os.PowerManager)getSystemService(POWER_SERVICE)).reboot(null)).setNegativeButton("Cancel",null).show());
        TextView footer=label("Inspired by libxzr/KonaBess. Edits the existing ten power levels. Nothing reboots automatically.",12,true);footer.setPadding(dp(24),dp(8),dp(24),dp(8));content.addView(footer);
        applyBackground();scroll.post(()->scroll.scrollTo(0,previousScroll));
    }
    private void alert(String title,String body){new AlertDialog.Builder(this).setTitle(title).setMessage(body).setPositiveButton("OK",null).show();}
    private void apply(){
        try{StringBuilder payload=new StringBuilder(hash());StringBuilder changes=new StringBuilder();JSONArray oldF=state.getJSONArray("frequencies"),oldL=state.getJSONArray("levels");long previous=Long.MAX_VALUE;int prevLevel=Integer.MAX_VALUE;boolean changed=false;
            for(int i=0;i<10;i++){long f=draftFrequency[i];int l=draftLevel[i];if(f<124800000||f>850000000||f%100000!=0||f>=previous||l>prevLevel)throw new IllegalArgumentException("Check frequency range, 0.1 MHz steps and descending frequency/voltage order.");previous=f;prevLevel=l;payload.append(' ').append(f).append(' ').append(l);if(f!=oldF.getLong(i)||l!=oldL.getInt(i)){changed=true;changes.append("Level ").append(i).append(": ").append(oldF.getLong(i)/1000000.0).append(" → ").append(f/1000000.0).append(" MHz; RPMh ").append(oldL.getInt(i)).append(" → ").append(l).append('\n');}}
            if(!changed)throw new IllegalArgumentException("No GPU changes selected.");
            new AlertDialog.Builder(this).setTitle("Write GPU trial to vendor_boot?").setMessage(changes+"\nThis can prevent booting. Confirm that your saved rollback ZIP and OrangeFox recovery are available. Keep the device powered until verification finishes.").setPositiveButton("Apply trial",(d,w)->run("apply",payload.toString())).setNegativeButton("Cancel",null).show();
        }catch(Exception e){alert("Cannot apply",e.getMessage());}
    }
    private void run(String operation,String payload){if(busy)return;busy=true;message="Working: "+operation+". Keep the device powered…";draw();
        worker.execute(()->{JSONObject result=null;String error=null;try{result=GpuTransport.request(operation,payload);}catch(Exception e){error=e.getMessage();}final JSONObject reply=result;final String fail=error;
            runOnUiThread(()->{if(isDestroyed())return;busy=false;if(reply!=null){state=reply;message=(operation.equals("apply")?"GPU table written and verified. Reboot when ready.":operation.equals("restore")?"Original vendor_boot restored and verified. Reboot when ready.":"Compatible firmware. Backup: "+(reply.optBoolean("backup")?"saved":"not created")+". Rollback export: "+(reply.optBoolean("exported")?"verified":"required before Apply")+".");if(!reply.optString("blockReason").isEmpty())message=reply.optString("blockReason")+". Your tuning profile stays saved.";}else{state=null;message="Operation failed: "+fail+". Refresh status before any further action.";alert("GPU operation failed",message);}draw();if(reply!=null&&operation.equals("status"))promptBackup();});
        });
    }
    private static String hex(byte[] b){StringBuilder out=new StringBuilder();for(byte v:b)out.append(String.format(java.util.Locale.US,"%02x",v&255));return out.toString();}
    @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);if(result!=RESULT_OK||data==null||data.getData()==null)return;
        if(request==PROFILE_EXPORT||request==PROFILE_IMPORT){profileDocument(request,data.getData());return;}if(request!=EXPORT)return;
        if(!ready())return;Uri uri=data.getData();String expected=state.optString("rollbackHash"),current=hash();busy=true;message="Saving and verifying recovery rollback ZIP…";draw();
        worker.execute(()->{JSONObject reply=null;String error=null;try{
            MessageDigest digest=MessageDigest.getInstance("SHA-256");long total=0;try(InputStream in=new FileInputStream(GpuTransport.ROLLBACK);OutputStream out=getContentResolver().openOutputStream(uri,"wt")){if(out==null)throw new IOException("Cannot open destination");byte[] b=new byte[65536];int n;while((n=in.read(b))!=-1){total+=n;if(total>120*1024*1024)throw new IOException("Oversized rollback ZIP");out.write(b,0,n);digest.update(b,0,n);}}
            if(!expected.equals(hex(digest.digest())))throw new IOException("Source rollback checksum mismatch");digest.reset();long read=0;
            try(InputStream in=getContentResolver().openInputStream(uri)){if(in==null)throw new IOException("Cannot verify saved ZIP");byte[] b=new byte[65536];int n;while((n=in.read(b))!=-1){read+=n;if(read>120*1024*1024)throw new IOException("Oversized saved ZIP");digest.update(b,0,n);}}
            if(read!=total||!expected.equals(hex(digest.digest())))throw new IOException("Saved rollback ZIP verification failed");reply=GpuTransport.request("exported",current+" "+expected);
        }catch(Exception e){error=e.getMessage();}final JSONObject r=reply;final String fail=error;runOnUiThread(()->{if(isDestroyed())return;busy=false;if(r!=null){state=r;message="Rollback ZIP saved and verified. Keep it accessible from recovery.";}else{message="Rollback export failed: "+fail;alert("Rollback export failed",message);}draw();});});
    }
}
