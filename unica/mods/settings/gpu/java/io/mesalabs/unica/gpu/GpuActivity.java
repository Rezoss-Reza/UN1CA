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

/** ROM-native image editor. No root manager or automatic partition writes. */
public final class GpuActivity extends Activity {
    private static final int EXPORT=51,PROFILE_EXPORT=52,PROFILE_IMPORT=53;
    private static final int[] LEVELS={1,16,48,52,56,60,64,72,80,96,128,144,192,224,256,288,320,336,384,400,416,432,448,464,480};
    private static final String[] NAMES={"RAW EXPERIMENTAL","RETENTION","MIN_SVS","LOW_SVS_D2","LOW_SVS_D1","LOW_SVS_D0","LOW_SVS","LOW_SVS_P1","LOW_SVS_L1","LOW_SVS_L2","SVS","SVS_L0","SVS_L1","SVS_L2","NOM","NOM_L0","NOM_L1","NOM_L2","TURBO","TURBO_L0","TURBO_L1","TURBO_L2","TURBO_L3","SUPER_TURBO","SUPER_TURBO_NO_CPR"};
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private GpuProfile draft;
    private int selectedTable;
    private boolean hasDraft,invalidSavedProfile;
    private String promptedImage="";
    private final java.util.List<LinearLayout> cards=new java.util.ArrayList<>();
    private LinearLayout page;
    private ScrollView scroller;
    private JSONObject state;
    private boolean busy,documentQueued;
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
    private TextView label(CharSequence value,int size,boolean secondary){
        TextView v=new TextView(this);v.setText(value);v.setTextSize(size);v.setIncludeFontPadding(true);
        v.setTextColor(themeColor(secondary?android.R.attr.textColorSecondary:android.R.attr.textColorPrimary,secondary?0xff999999:0xffeeeeee));
        v.setPadding(0,dp(2),0,dp(2));return v;
    }
    private void section(String title){TextView v=label(title,14,true);v.setPadding(dp(24),dp(14),dp(24),dp(8));v.setAccessibilityHeading(true);content.addView(v);}
    private void card(){
        page=new LinearLayout(this);page.setOrientation(LinearLayout.VERTICAL);page.setPadding(0,dp(4),0,dp(4));page.setClipToOutline(true);
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.bottomMargin=dp(16);content.addView(page,p);cards.add(page);
    }
    private void description(String title,CharSequence summary){
        LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);box.setPadding(dp(24),dp(16),dp(24),dp(16));
        TextView name=label(title,20,false);name.setTypeface(null,android.graphics.Typeface.BOLD);box.addView(name);
        if(summary!=null){TextView detail=label(summary,14,true);LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.topMargin=dp(4);box.addView(detail,p);}page.addView(box);
    }
    private void divider(){
        if(page.getChildCount()==0)return;View v=new View(this);int color=themeColor(android.R.attr.textColorSecondary,0xff888888);v.setBackgroundColor((color&0xffffff)|0x26000000);
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,dp(1));p.setMargins(dp(24),0,dp(24),0);page.addView(v,p);
    }
    private void row(String title,CharSequence summary,boolean enabled,boolean action,View.OnClickListener listener){
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
    private String levelName(int value){for(int i=0;i<LEVELS.length;i++)if(LEVELS[i]==value)return value+" · "+NAMES[i];return value+" · custom raw request";}
    private int warningColor(){return (getResources().getConfiguration().uiMode&0x30)==0x20?0xffff8a80:0xffb00020;}
    private CharSequence riskText(String value,boolean risk){android.text.SpannableString text=new android.text.SpannableString(value);if(risk)text.setSpan(new android.text.style.ForegroundColorSpan(warningColor()),0,text.length(),android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);return text;}
    private SharedPreferences profileStore(){return createDeviceProtectedStorageContext().getSharedPreferences("unica_gpu_profile",MODE_PRIVATE);}
    private GpuProfile profile()throws Exception {return draft.copy();}
    private void setProfile(GpuProfile p,boolean persist)throws Exception {
        GpuProfile checked=GpuProfile.parse(p.encode());
        if(persist&&!profileStore().edit().putString("profile",new String(checked.encode(),java.nio.charset.StandardCharsets.UTF_8)).commit())throw new IOException("Cannot save tuning profile");
        draft=checked;hasDraft=true;invalidSavedProfile=false;
    }
    private void loadSavedProfile(){
        String saved=profileStore().getString("profile",null);if(saved==null)return;
        try{setProfile(GpuProfile.parse(saved.getBytes(java.nio.charset.StandardCharsets.UTF_8)),false);}catch(Exception e){invalidSavedProfile=true;alert("Saved profile unavailable","The saved data was kept for recovery. "+e.getMessage());}
    }
    private void syncDraft(){if(state==null||hasDraft||invalidSavedProfile)return;try{setProfile(imageProfile(),true);}catch(Exception e){message="Cannot initialize tuning profile: "+e.getMessage();}}
    private GpuProfile imageProfile()throws Exception {return GpuProfile.fromTables("My GPU profile",android.os.Build.FINGERPRINT,state.getJSONArray("tables"));}
    private void saveDraft(GpuProfile p)throws Exception {p.firmware=android.os.Build.FINGERPRINT;setProfile(p,true);}
    private GpuProfile.Bin bin(){return draft.tables[selectedTable];}
    private String warning(int id,int index,GpuProfile.Row row){
        StringBuilder out=new StringBuilder();if(row.maximum>9)out.append("This driver clamps active-row DDR requests above 9 to 9. ");if(GpuProfile.outsideStock(row.voltage))out.append("Outside stock RPMh requests. ");if(!GpuProfile.stockFrequency(id,row.frequency))out.append("Outside stock frequency table. ");
        try{GpuProfile.Bin stock=GpuProfile.stock().tables[id];if(index>=stock.rows.size())out.append("Added power level. ");else{GpuProfile.Row old=stock.rows.get(index);if(row.bus!=old.bus||row.minimum!=old.minimum||row.maximum!=old.maximum||row.hasAcd!=old.hasAcd||(row.hasAcd&&row.acd!=old.acd))out.append("Bus/ACD differs from stock. ");}}catch(Exception e){out.append("Stock comparison unavailable. ");}
        return out.toString();
    }
    private CharSequence review(GpuProfile p){
        android.text.SpannableStringBuilder text=new android.text.SpannableStringBuilder();text.append(p.name).append("\n");
        for(int id=0;id<2;id++){GpuProfile.Bin b=p.tables[id];text.append("\nTable "+id+" · "+b.rows.size()+" levels · initial "+b.initial+" · minimum "+b.minimum+"\n");for(int i=0;i<b.rows.size();i++){GpuProfile.Row r=b.rows.get(i);String note=warning(id,i,r);text.append(riskText("Level "+i+": "+mhz(r.frequency)+" MHz; RPMh "+r.voltage+"; bus "+r.minimum+"/"+r.bus+"/"+r.maximum+"; ACD "+(r.hasAcd?String.format(java.util.Locale.US,"0x%08x",r.acd):"none")+"\n"+note+(note.isEmpty()?"":"\n"),!note.isEmpty()));}}
        text.append(riskText("\nExperimental values can freeze the GPU or prevent booting. Requests are not millivolts; the driver rounds to available corners. Raw request 1 is not a standard named corner. Neither stock nor red values guarantee stability. Keep the matching recovery rollback available.",true));return text;
    }
    private void chooseProfile(int request){
        Intent i=new Intent(request==PROFILE_EXPORT?Intent.ACTION_CREATE_DOCUMENT:Intent.ACTION_OPEN_DOCUMENT);i.addCategory(Intent.CATEGORY_OPENABLE);i.setType(request==PROFILE_EXPORT?"application/json":"*/*");
        if(request==PROFILE_EXPORT)i.putExtra(Intent.EXTRA_TITLE,"UN1CA-dm3q-GPU-profile.json");
        try{startActivityForResult(i,request);}catch(ActivityNotFoundException e){alert("File picker unavailable",e.getMessage());}
    }
    private void createBackup(){
        if(!ready())return;
        if(state.optBoolean("canRestart")){
            String current=hash(),baseline=state.optString("baselineHash"),previous=state.optString("refreshHash");
            new AlertDialog.Builder(this).setTitle("Restart backup for this ROM?").setMessage("The previous backup refresh belongs to a different image. Preserve and verify its rollback, journal and staged files in protected /data storage before freeing their cache space. Then create this ROM's clean backup. Save the NEW rollback ZIP to PC or USB before Apply. Your tuning profile stays saved. This does not flash any image.").setPositiveButton("Restart backup",(d,w)->run("restart",current+" "+baseline+" "+previous)).setNegativeButton("Cancel",null).show();
        }else if(state.optBoolean("canRenew")){
            String current=hash(),baseline=state.optString("baselineHash");
            new AlertDialog.Builder(this).setTitle(state.optBoolean("renewing")?"Resume backup refresh?":"Create backup for current ROM?").setMessage("The current image differs from the saved original. Copy and verify the old rollback in protected /data storage before freeing its /cache space. Then back up this ROM's clean vendor_boot and save the new rollback ZIP to PC or USB. Your tuning profile stays saved. This does not flash either image.").setPositiveButton("Create backup",(d,w)->run("renew",current+" "+baseline)).setNegativeButton("Cancel",null).show();
        }else run("backup",hash());
    }
    private void promptBackup(){
        if(!ready()||(!state.optBoolean("canRenew")&&!state.optBoolean("canRestart"))||hash().equals(promptedImage))return;promptedImage=hash();createBackup();
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
        new AlertDialog.Builder(this).setTitle("Load imported tuning profile?").setMessage(review(p)).setPositiveButton("Load profile",(d,w)->{try{setProfile(p,true);message="Imported profile saved. Loading did not write vendor_boot. Review before Apply.";draw();}catch(Exception e){alert("Cannot save profile",e.getMessage());}}).setNegativeButton("Cancel",null).show();
    }
    private void editFrequency(int index){
        if(!ready())return;EditText input=numberInput(mhz(bin().rows.get(index).frequency),true);
        LinearLayout body=dialogBody();body.addView(label("GPU frequency (MHz)",14,true));body.addView(input);body.addView(label(riskText("Up to six decimal places (1 Hz). Positive u32 Hz input only; this is a storage limit, not a supported clock range. Non-stock frequencies appear in red and can prevent booting.",true),14,true));
        AlertDialog d=new AlertDialog.Builder(this).setTitle("Power level "+index).setView(body).setPositiveButton("Save",null).setNegativeButton("Cancel",null).create();
        d.setOnShowListener(v->d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(w->{try{GpuProfile next=profile();next.tables[selectedTable].rows.get(index).frequency=new BigDecimal(input.getText().toString()).movePointRight(6).longValueExact();saveDraft(next);d.dismiss();draw();}catch(Exception e){input.setError(e.getMessage());}}));d.show();
    }
    private LinearLayout dialogBody(){LinearLayout body=new LinearLayout(this);body.setOrientation(LinearLayout.VERTICAL);body.setPadding(dp(24),dp(12),dp(24),dp(12));return body;}
    private EditText numberInput(String value,boolean decimal){EditText input=new EditText(this);input.setSingleLine(true);input.setTextSize(20);input.setMinHeight(dp(64));input.setPadding(dp(12),dp(12),dp(12),dp(12));input.setInputType(decimal?InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_FLAG_DECIMAL:InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);input.setText(value);input.setSelectAllOnFocus(true);return input;}
    private void saveVoltage(int index,int value){try{GpuProfile next=profile();next.tables[selectedTable].rows.get(index).voltage=value;saveDraft(next);draw();}catch(Exception e){alert("Cannot save voltage request",e.getMessage());}}
    private void customVoltage(int index){EditText input=numberInput(Integer.toString(bin().rows.get(index).voltage),false);LinearLayout body=dialogBody();body.addView(label(riskText("Raw RPMh request, 1–480. This is not millivolts or a hardware-supported range. Request 1 and values outside stock are experimental.",true),14,true));body.addView(input);AlertDialog d=new AlertDialog.Builder(this).setTitle("Custom voltage request").setView(body).setPositiveButton("Save",null).setNegativeButton("Cancel",null).create();d.setOnShowListener(v->d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(w->{try{int value=Integer.parseInt(input.getText().toString());GpuProfile next=profile();next.tables[selectedTable].rows.get(index).voltage=value;saveDraft(next);d.dismiss();draw();}catch(Exception e){input.setError(e.getMessage());}}));d.show();}
    private void editLevel(int index){
        if(!ready())return;String[] labels=new String[LEVELS.length];for(int i=0;i<LEVELS.length;i++)labels[i]=levelName(LEVELS[i])+(GpuProfile.outsideStock(LEVELS[i])?" · outside stock":"");
        ArrayAdapter<String> choices=new ArrayAdapter<String>(this,android.R.layout.simple_list_item_1,labels){@Override public View getView(int position,View convert,ViewGroup parent){TextView view=(TextView)super.getView(position,convert,parent);view.setTextColor(GpuProfile.outsideStock(LEVELS[position])?warningColor():themeColor(android.R.attr.textColorPrimary,0xffeeeeee));view.setMinHeight(dp(64));return view;}};
        new AlertDialog.Builder(this).setTitle("Voltage request · table "+selectedTable+" level "+index).setAdapter(choices,(d,which)->{if(GpuProfile.outsideStock(LEVELS[which]))new AlertDialog.Builder(this).setTitle("Use experimental RPMh request?").setMessage(riskText(levelName(LEVELS[which])+" is outside this firmware's stock request set. The driver can map it to a different corner. It can cause instability or prevent booting. This saves the draft only.",true)).setPositiveButton("Use request",(v,w)->saveVoltage(index,LEVELS[which])).setNegativeButton("Cancel",null).show();else saveVoltage(index,LEVELS[which]);}).setNeutralButton("Custom request",(d,w)->customVoltage(index)).setNegativeButton("Cancel",null).show();
    }
    private void editBus(int index){
        GpuProfile.Row row=bin().rows.get(index);LinearLayout body=dialogBody();String[] names={"Bus frequency index","Bus minimum index","Bus maximum index","ACD cell (decimal/0x hex, or none)"};String[] values={""+row.bus,""+row.minimum,""+row.maximum,row.hasAcd?String.format(java.util.Locale.US,"0x%08x",row.acd):"none"};EditText[] inputs=new EditText[4];body.addView(label(riskText("Advanced: raw u32 bus requests must satisfy min ≤ frequency ≤ max. This driver clamps requests above 9 on active rows; the final row must use 0–9. ACD is a raw bitfield. Changes can prevent booting.",true),14,true));for(int i=0;i<4;i++){body.addView(label(names[i],14,true));inputs[i]=numberInput(values[i],false);body.addView(inputs[i]);}ScrollView scroll=new ScrollView(this);scroll.addView(body);
        AlertDialog d=new AlertDialog.Builder(this).setTitle("Bus and ACD · level "+index).setView(scroll).setPositiveButton("Save",null).setNegativeButton("Cancel",null).create();d.setOnShowListener(v->d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(w->{try{GpuProfile next=profile();GpuProfile.Row r=next.tables[selectedTable].rows.get(index);r.bus=Long.parseLong(inputs[0].getText().toString());r.minimum=Long.parseLong(inputs[1].getText().toString());r.maximum=Long.parseLong(inputs[2].getText().toString());String acd=inputs[3].getText().toString().trim();r.hasAcd=!acd.equalsIgnoreCase("none");r.acd=r.hasAcd?(acd.startsWith("0x")?Long.parseLong(acd.substring(2),16):Long.parseLong(acd)):0;saveDraft(next);d.dismiss();draw();}catch(Exception e){inputs[0].setError(e.getMessage());}}));d.show();
    }
    private void editInitial(boolean minimum){EditText input=numberInput(Integer.toString(minimum?bin().minimum:bin().initial),false);LinearLayout body=dialogBody();body.addView(label("Index from 0 to "+(bin().rows.size()-1),14,true));body.addView(input);AlertDialog d=new AlertDialog.Builder(this).setTitle(minimum?"Initial minimum level":"Initial power level").setView(body).setPositiveButton("Save",null).setNegativeButton("Cancel",null).create();d.setOnShowListener(v->d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(w->{try{GpuProfile next=profile();int value=Integer.parseInt(input.getText().toString());if(minimum)next.tables[selectedTable].minimum=value;else next.tables[selectedTable].initial=value;saveDraft(next);d.dismiss();draw();}catch(Exception e){input.setError(e.getMessage());}}));d.show();}
    private void removeLevel(int index){new AlertDialog.Builder(this).setTitle("Remove power level "+index+"?").setMessage(riskText("This changes the saved table structure. Remaining levels are renumbered and initial/minimum references adjusted. Keep at least two levels. Apply is a separate action.",true)).setPositiveButton("Remove",(d,w)->{try{GpuProfile next=profile();GpuProfile.Bin b=next.tables[selectedTable];b.rows.remove(index);b.initial=Math.min(b.initial>index?b.initial-1:b.initial,b.rows.size()-1);b.minimum=Math.min(b.minimum>index?b.minimum-1:b.minimum,b.rows.size()-1);saveDraft(next);draw();}catch(Exception e){alert("Cannot remove level",e.getMessage());}}).setNegativeButton("Cancel",null).show();}
    private void addLevel(){try{GpuProfile next=profile();GpuProfile.Bin b=next.tables[selectedTable];GpuProfile.Row last=b.rows.get(b.rows.size()-1),r=new GpuProfile.Row();r.frequency=last.frequency/2;r.voltage=last.voltage;r.bus=last.bus;r.minimum=last.minimum;r.maximum=last.maximum;r.hasAcd=last.hasAcd;r.acd=last.acd;b.rows.add(r);saveDraft(next);draw();alert("Draft level added","Review its frequency, RPMh, bus and ACD values. These draft values are not a stability recommendation.");}catch(Exception e){alert("Cannot add level",e.getMessage());}}
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
        description(hasDraft?draft.name:"No saved profile","Values are saved automatically and retained across ROM updates that preserve Settings data. Imported values are never applied automatically.");
        row("Back up profile","Export a shareable JSON file with frequencies and voltage levels",hasDraft,false,v->chooseProfile(PROFILE_EXPORT));
        row("Import profile","UN1CA JSON or original konabess:// export · review both tables",true,false,v->chooseProfile(PROFILE_IMPORT));
        row("Load current image values","Replace the saved draft with the current image's table",ready(),false,v->new AlertDialog.Builder(this).setTitle("Replace saved profile values?").setMessage("This replaces the saved draft. Back up your profile first if you want to keep it.").setPositiveButton("Load values",(d,w)->{try{setProfile(imageProfile(),true);draw();}catch(Exception e){alert("Cannot load values",e.getMessage());}}).setNegativeButton("Cancel",null).show());
        section("Recovery rollback");card();
        row(state!=null&&state.optBoolean("canRestart")?"Restart backup for current ROM":state!=null&&state.optBoolean("renewing")?"Resume backup refresh":state!=null&&state.optBoolean("staleBackup")?"Create backup for current ROM":"Create or verify original backup",state!=null&&state.optBoolean("staleBackup")?"Verify old rollback in /data, free its cache space and back up this ROM; profile stays saved":state!=null&&state.optBoolean("backup")?"Original image saved":"Save the original before making changes",ready()&&(state.optBoolean("canRestart")||state.optBoolean("canRenew")||(!state.optBoolean("staleBackup")&&!state.optBoolean("renewing")&&!state.optBoolean("interrupted")&&(!state.optBoolean("backup")||state.optBoolean("original")))),false,v->createBackup());
        row("Save rollback ZIP",state!=null&&state.optBoolean("exported")?"Saved copy verified":"Required before Apply · choose PC or USB storage",ready()&&state.optBoolean("backup")&&state.optBoolean("canRestore")&&!state.optBoolean("interrupted")&&!state.optBoolean("renewing"),false,v->{Intent i=new Intent(Intent.ACTION_CREATE_DOCUMENT);i.addCategory(Intent.CATEGORY_OPENABLE);i.setType("application/zip");i.putExtra(Intent.EXTRA_TITLE,"UN1CA-dm3q-GPU-rollback.zip");try{startActivityForResult(i,EXPORT);}catch(ActivityNotFoundException e){alert("File picker unavailable",e.getMessage());}});
        row("Recovery instructions","Restore through OrangeFox if Android cannot start",true,false,v->alert("If Android cannot start","Boot into OrangeFox using the device's recovery procedure. Mount Cache. Flash /cache/unica-gpu/rollback.zip, or flash the saved rollback ZIP from USB/PC storage. It verifies and restores the exact pre-trial vendor_boot image. Reboot manually after success. Keep a matching full ROM available if recovery restoration fails. Never use this ZIP after changing firmware."));
        section("GPU tables");card();
        row("About GPU tuning","Full-Hz frequencies · RPMh requests · bus and ACD",true,false,v->alert("GPU tuning", "Both tables are stored in firmware. The driver chooses the active table by chip/SKU; choosing a table here only chooses which draft to edit. Up to 15 entries per table, within the original FDT space. RPMh requests are mapped to available command-DB corners, not millivolts. Red marks values outside stock. None of these settings are a stability guarantee."));
        if(hasDraft){
            row("Edit table", "Table "+selectedTable+" · "+bin().rows.size()+" power levels",true,false,v->new AlertDialog.Builder(this).setTitle("Edit GPU table").setSingleChoiceItems(new String[]{"Table 0 · SKU-specific","Table 1 · fallback"},selectedTable,(d,w)->{selectedTable=w;d.dismiss();draw();}).setNegativeButton("Cancel",null).show());
            row("Initial power level",Integer.toString(bin().initial),ready(),false,v->editInitial(false));row("Initial minimum level",Integer.toString(bin().minimum),ready(),false,v->editInitial(true));
            description("Experimental settings",riskText("Red values are outside stock or change bus/ACD behavior. They may map to different corners, freeze the GPU or prevent booting. Request 1 is experimental and is not 1 mV.",true));
            for(int index=0;index<bin().rows.size();index++){final int row=index;GpuProfile.Row r=bin().rows.get(index);boolean editable=ready()&&!state.optBoolean("interrupted")&&!state.optBoolean("renewing");section("Table "+selectedTable+" · Power level "+row);card();
                row("GPU frequency",riskText(mhz(r.frequency)+" MHz"+(GpuProfile.stockFrequency(selectedTable,r.frequency)?"":" · outside stock"),!GpuProfile.stockFrequency(selectedTable,r.frequency)),editable,false,v->editFrequency(row));
                row("Voltage request",riskText(levelName(r.voltage)+(GpuProfile.outsideStock(r.voltage)?" · outside stock":""),GpuProfile.outsideStock(r.voltage)),editable,false,v->editLevel(row));
                row("Bus and ACD",riskText("Bus "+r.minimum+" / "+r.bus+" / "+r.maximum+" · ACD "+(r.hasAcd?String.format(java.util.Locale.US,"0x%08x",r.acd):"none"),!warning(selectedTable,row,r).isEmpty()),editable,false,v->editBus(row));
                row("Remove power level","Renumber remaining levels and adjust references",editable&&bin().rows.size()>2,false,v->removeLevel(row));
            }
            card();button("Add power level",ready()&&!state.optBoolean("interrupted")&&!state.optBoolean("renewing"),v->addLevel());
        }
        card();button("Apply GPU table",hasDraft&&ready()&&state.optBoolean("exported")&&state.optBoolean("canRestore")&&!state.optBoolean("interrupted"),v->apply());
        section("Restore and restart");card();
        row("Restore original vendor_boot","Restore the pre-trial image, then reboot",ready()&&state.optBoolean("canRestore"),false,v->new AlertDialog.Builder(this).setTitle("Restore original GPU image?").setMessage("This restores the full vendor_boot image captured before this trial, including its original GPU table. Reboot after restoration succeeds.").setPositiveButton("Restore",(d,w)->run("restore",hash())).setNegativeButton("Cancel",null).show());
        row("Reboot now","Only after Apply or Restore reports success",ready()&&!state.optBoolean("interrupted")&&!state.optBoolean("renewing"),false,v->new AlertDialog.Builder(this).setTitle("Reboot device?").setMessage("Keep the rollback ZIP and recovery available. Only reboot after Apply or Restore reports success.").setPositiveButton("Reboot",(d,w)->((android.os.PowerManager)getSystemService(POWER_SERVICE)).reboot(null)).setNegativeButton("Cancel",null).show());
        TextView footer=label("Inspired by libxzr/KonaBess. Both Samsung GPU tables, within the inspected FDT space. Nothing applies or reboots automatically.",12,true);footer.setPadding(dp(24),dp(8),dp(24),dp(8));content.addView(footer);
        applyBackground();scroll.post(()->scroll.scrollTo(0,previousScroll));
    }
    private void alert(String title,String body){new AlertDialog.Builder(this).setTitle(title).setMessage(body).setPositiveButton("OK",null).show();}
    private CharSequence applyReview(GpuProfile next,GpuProfile old){
        android.text.SpannableStringBuilder text=new android.text.SpannableStringBuilder("Changes from the current image:\n");
        for(int id=0;id<2;id++){GpuProfile.Bin a=old.tables[id],b=next.tables[id];text.append("\nTable "+id+": "+a.rows.size()+" → "+b.rows.size()+" levels; initial "+a.initial+" → "+b.initial+"; minimum "+a.minimum+" → "+b.minimum+"\n");
            for(int i=0;i<Math.max(a.rows.size(),b.rows.size());i++){if(i>=b.rows.size()){text.append("Level "+i+": removed\n");continue;}GpuProfile.Row n=b.rows.get(i);if(i>=a.rows.size()){text.append(riskText("Level "+i+": added\n",true));continue;}GpuProfile.Row o=a.rows.get(i);StringBuilder line=new StringBuilder();if(n.frequency!=o.frequency)line.append("MHz "+mhz(o.frequency)+" → "+mhz(n.frequency)+"; ");if(n.voltage!=o.voltage)line.append("RPMh "+o.voltage+" → "+n.voltage+"; ");if(n.bus!=o.bus||n.minimum!=o.minimum||n.maximum!=o.maximum)line.append("bus "+o.minimum+"/"+o.bus+"/"+o.maximum+" → "+n.minimum+"/"+n.bus+"/"+n.maximum+"; ");if(n.hasAcd!=o.hasAcd||(n.hasAcd&&n.acd!=o.acd))line.append("ACD changed; ");if(line.length()>0)text.append(riskText("Level "+i+": "+line+"\n",!warning(id,i,n).isEmpty()));}
        }text.append("\nComplete values to write:\n").append(review(next));return text;
    }
    private void apply(){
        try{GpuProfile p=profile(),old=imageProfile();String payload=p.payload(hash());if(p.tablesJson().toString().equals(old.tablesJson().toString()))throw new IllegalArgumentException("No GPU changes selected.");
            new AlertDialog.Builder(this).setTitle("Write both GPU tables to vendor_boot?").setMessage(applyReview(p,old)).setPositiveButton("Apply trial",(d,w)->run("apply2",payload)).setNegativeButton("Cancel",null).show();
        }catch(Exception e){alert("Cannot apply",e.getMessage());}
    }
    private void run(String operation,String payload){if(busy)return;busy=true;message="Working: "+operation+". Keep the device powered…";draw();
        worker.execute(()->{JSONObject result=null;String error=null;try{result=GpuTransport.request(operation,payload);}catch(Exception e){error=e.getMessage();}final JSONObject reply=result;final String fail=error;
            runOnUiThread(()->{if(isDestroyed())return;busy=false;if(reply!=null){state=reply;message=(operation.startsWith("apply")?"GPU table written and verified. Reboot when ready.":operation.equals("restore")?"Original vendor_boot restored and verified. Reboot when ready.":"Compatible firmware. Backup: "+(reply.optBoolean("backup")?"saved":"not created")+". Rollback export: "+(reply.optBoolean("exported")?"verified":"required before Apply")+".");if(!reply.optString("blockReason").isEmpty())message=reply.optString("blockReason")+". Your tuning profile stays saved.";}else{state=null;message="Operation failed: "+fail+". Refresh status before any further action.";alert("GPU operation failed",message);}draw();if(!documentQueued&&reply!=null&&operation.equals("status"))promptBackup();});
        });
    }
    private static String hex(byte[] b){StringBuilder out=new StringBuilder();for(byte v:b)out.append(String.format(java.util.Locale.US,"%02x",v&255));return out.toString();}
    @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);if(result!=RESULT_OK||data==null||data.getData()==null)return;handleDocument(request,data.getData());}
    private void handleDocument(int request,Uri uri){
        // Activity recreation can deliver a picker result while initial status is
        // running. Queue it behind that operation instead of dropping the URI.
        if(busy){documentQueued=true;worker.execute(()->runOnUiThread(()->{if(!isDestroyed())handleDocument(request,uri);}));return;}documentQueued=false;
        if(request==PROFILE_EXPORT||request==PROFILE_IMPORT){profileDocument(request,uri);return;}if(request!=EXPORT)return;
        if(!ready()){alert("Rollback unavailable","Refresh compatibility before exporting the rollback ZIP.");return;}String expected=state.optString("rollbackHash"),current=hash();busy=true;message="Saving and verifying recovery rollback ZIP…";draw();
        worker.execute(()->{JSONObject reply=null;String error=null;try{
            MessageDigest digest=MessageDigest.getInstance("SHA-256");long total=0;try(InputStream in=new FileInputStream(GpuTransport.ROLLBACK);OutputStream out=getContentResolver().openOutputStream(uri,"wt")){if(out==null)throw new IOException("Cannot open destination");byte[] b=new byte[65536];int n;while((n=in.read(b))!=-1){total+=n;if(total>120*1024*1024)throw new IOException("Oversized rollback ZIP");out.write(b,0,n);digest.update(b,0,n);}}
            if(!expected.equals(hex(digest.digest())))throw new IOException("Source rollback checksum mismatch");digest.reset();long read=0;
            try(InputStream in=getContentResolver().openInputStream(uri)){if(in==null)throw new IOException("Cannot verify saved ZIP");byte[] b=new byte[65536];int n;while((n=in.read(b))!=-1){read+=n;if(read>120*1024*1024)throw new IOException("Oversized saved ZIP");digest.update(b,0,n);}}
            if(read!=total||!expected.equals(hex(digest.digest())))throw new IOException("Saved rollback ZIP verification failed");reply=GpuTransport.request("exported",current+" "+expected);
        }catch(Exception e){error=e.getMessage();}final JSONObject r=reply;final String fail=error;runOnUiThread(()->{if(isDestroyed())return;busy=false;if(r!=null){state=r;message="Rollback ZIP saved and verified. Keep it accessible from recovery.";}else{message="Rollback export failed: "+fail;alert("Rollback export failed",message);}draw();});});
    }
}
