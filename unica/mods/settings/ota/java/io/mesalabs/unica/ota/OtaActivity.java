/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.mesalabs.unica.ota;
import android.app.*;import android.content.*;import android.net.Uri;import android.os.*;import android.view.*;import android.widget.*;
import java.io.*;import java.util.*;import java.util.concurrent.*;

public final class OtaActivity extends Activity {
 private static final String FOLDER="1AwB5M1gD24vCJm97u0CtZSdWzF0Vus7B";
 private static final File REQUEST=new File("/data/misc/unica_ota/request"), ZIP=new File(REQUEST,"update.zip");
 private final ExecutorService worker=Executors.newSingleThreadExecutor();
 private TextView status;private LinearLayout page,menu;private boolean busy;
 private final List<View> actions=new ArrayList<>(),cards=new ArrayList<>(),dividers=new ArrayList<>();
 private final List<TextView> primary=new ArrayList<>(),secondary=new ArrayList<>();
 private int dp(int value){return Math.round(value*getResources().getDisplayMetrics().density);}
 @Override public void onCreate(Bundle b){super.onCreate(b);setTitle("OTA Update");if(getActionBar()!=null)getActionBar().setDisplayHomeAsUpEnabled(true);
  if(getActionBar()!=null)getActionBar().hide();
  LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);setContentView(root);
  root.setOnApplyWindowInsetsListener((v,insets)->{android.graphics.Insets bars=insets.getInsets(WindowInsets.Type.systemBars());v.setPadding(bars.left,bars.top,bars.right,bars.bottom);return insets;});
  LinearLayout header=new LinearLayout(this);header.setGravity(Gravity.CENTER_VERTICAL);header.setPadding(dp(8),0,dp(16),0);root.addView(header,new LinearLayout.LayoutParams(-1,dp(64)));
  TextView back=text("‹",34,false);back.setGravity(Gravity.CENTER);back.setContentDescription("Navigate up");back.setBackground(ripple());back.setOnClickListener(v->onBackPressed());back.setFocusable(true);header.addView(back,new LinearLayout.LayoutParams(dp(48),dp(48)));
  TextView title=text("OTA Update",22,false);header.addView(title,new LinearLayout.LayoutParams(-1,-2));
  ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.setClipToPadding(false);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
  page=new LinearLayout(this);page.setOrientation(LinearLayout.VERTICAL);page.setPadding(dp(12),dp(8),dp(12),dp(24));scroll.addView(page);
  LinearLayout installed=card();label(installed,"Installed software");TextView installedInfo=text("",14,true);installedInfo.setPadding(0,dp(6),0,0);installed.addView(installedInfo);
  menu=card();menu.setPadding(0,0,0,0);
  action("Download latest OTA","Open the download folder in your browser",()->openDownloadFolder());
  action("Select a local OTA ZIP","Choose a downloaded update and check compatibility",()->{Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("application/zip").addCategory(Intent.CATEGORY_OPENABLE);startActivityForResult(i,1);});
  action("Install and reboot","Restart into OrangeFox to install the verified update",()->{if(!ZIP.exists()){show("Select the downloaded OTA ZIP first.");return;}new AlertDialog.Builder(this).setTitle("Install OTA update?").setMessage("The phone will restart into OrangeFox. Press Volume Down during its initial 5-second countdown to cancel.").setNegativeButton("Cancel",null).setPositiveButton("Install",(d,w)->run(()->install())).show();});
  LinearLayout progress=card();label(progress,"Update status");status=text("Select an OTA ZIP to check for an update.",14,true);status.setPadding(0,dp(6),0,0);status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);progress.addView(status);
  try{installedInfo.setText(OtaPackage.prop("ro.unica.version")+"\nRelease: "+OtaPackage.prop("ro.unica.timestamp"));
   File result=new File("/data/misc/unica_ota/staged/result");if(result.exists())try(InputStream in=new FileInputStream(result)){show("Last installation: "+new String(OtaPackage.read(in,4096),"UTF-8"));}
  }catch(Exception e){show("Updater setup: "+e.getMessage());}
 }
 @Override protected void onResume(){super.onResume();
  boolean dark=(getResources().getConfiguration().uiMode&0x30)==0x20;
  boolean transparent=android.provider.Settings.System.getInt(getContentResolver(),"unica_settings_bg_transparency_enabled",0)!=0;
  int level=Math.max(70,Math.min(100,android.provider.Settings.System.getInt(getContentResolver(),"unica_settings_bg_transparency_level",70)));
  int bg=romColor("sesl_round_and_bgcolor_"+(dark?"dark":"light"),dark?0xff000000:0xfff2f2f2);int surface=romColor("sesl_background_color_"+(dark?"dark":"light"),dark?0xff17171a:0xffffffff);
  if(transparent){surface=(surface&0xffffff)|((255-level*255/100)<<24);bg=android.graphics.Color.TRANSPARENT;getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER);}else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER);
  getWindow().setFormat(transparent?android.graphics.PixelFormat.TRANSLUCENT:android.graphics.PixelFormat.OPAQUE);
  getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(bg));
  for(View card:cards){android.graphics.drawable.GradientDrawable shape=new android.graphics.drawable.GradientDrawable();shape.setColor(surface);shape.setCornerRadius(dp(28));card.setBackground(shape);}
  android.content.res.TypedArray colors=obtainStyledAttributes(new int[]{android.R.attr.textColorPrimary,android.R.attr.textColorSecondary});
  android.content.res.ColorStateList main=colors.getColorStateList(0),detail=colors.getColorStateList(1);colors.recycle();
  for(TextView text:primary)if(main!=null)text.setTextColor(main);
  for(TextView text:secondary)if(detail!=null)text.setTextColor(detail);
  for(View divider:dividers)divider.setBackgroundColor(dark?0x26ffffff:0x1a000000);
 }
 private int romColor(String name,int fallback){int id=getResources().getIdentifier(name,"color",getPackageName());return id==0?fallback:getColor(id);}
 private android.graphics.drawable.Drawable ripple(){android.util.TypedValue value=new android.util.TypedValue();getTheme().resolveAttribute(android.R.attr.selectableItemBackground,value,true);return value.resourceId==0?null:getDrawable(value.resourceId);}
 private TextView text(String value,int size,boolean detail){TextView text=new TextView(this);text.setText(value);text.setTextSize(size);text.setTextDirection(View.TEXT_DIRECTION_LOCALE);(detail?secondary:primary).add(text);return text;}
 private LinearLayout card(){LinearLayout card=new LinearLayout(this);card.setOrientation(LinearLayout.VERTICAL);card.setPadding(dp(20),dp(18),dp(20),dp(18));card.setClipToOutline(true);LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(-1,-2);params.bottomMargin=dp(20);page.addView(card,params);cards.add(card);return card;}
 private void label(LinearLayout card,String value){card.addView(text(value,20,false));}
 private interface Work{void go()throws Exception;}
 private void action(String title,String summary,Runnable work){
  if(!actions.isEmpty()){View line=new View(this);LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(-1,dp(1));params.setMarginStart(dp(20));params.setMarginEnd(dp(20));menu.addView(line,params);dividers.add(line);}
  LinearLayout row=new LinearLayout(this);row.setOrientation(LinearLayout.VERTICAL);row.setGravity(Gravity.CENTER_VERTICAL);row.setPadding(dp(20),dp(16),dp(20),dp(16));row.setMinimumHeight(dp(80));row.setBackground(ripple());row.setFocusable(true);row.setOnClickListener(v->work.run());
  row.addView(text(title,20,false));TextView detail=text(summary,14,true);detail.setPadding(0,dp(4),0,0);row.addView(detail);menu.addView(row,new LinearLayout.LayoutParams(-1,-2));actions.add(row);
 }
 private void show(String s){runOnUiThread(()->{if(!isFinishing())status.setText(s);});}
 private void setActionsEnabled(boolean enabled){for(View row:actions){row.setEnabled(enabled);row.setAlpha(enabled?1f:0.5f);}}
 private void run(Work job){if(busy)return;busy=true;setActionsEnabled(false);worker.execute(()->{try{job.go();}catch(Exception e){android.util.Log.e("UnicaOTA","Update operation failed",e);show(e.getMessage()==null?"Update operation failed":e.getMessage());}finally{runOnUiThread(()->{busy=false;setActionsEnabled(true);});}});}
 @Override public boolean onOptionsItemSelected(MenuItem i){if(i.getItemId()==android.R.id.home){if(!busy)finish();return true;}return super.onOptionsItemSelected(i);}
 @Override public void onBackPressed(){if(busy)Toast.makeText(this,"Wait for the update operation to finish",Toast.LENGTH_SHORT).show();else super.onBackPressed();}
 @Override protected void onDestroy(){worker.shutdown();super.onDestroy();}
 @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);if(request==1&&result==RESULT_OK&&data!=null){Uri uri=data.getData();run(()->selectZip(uri));}}
 private void selectZip(Uri uri)throws Exception{
  File part=new File(REQUEST,"local.part");String step="Opening selected OTA";
  try{
   try(InputStream in=getContentResolver().openInputStream(uri)){
    step="Creating OTA staging file";
    try(FileOutputStream out=new FileOutputStream(part)){step="Copying selected OTA";copy(in,out);android.system.Os.fchmod(out.getFD(),0640);out.getFD().sync();}
   }
   step="Verifying selected OTA";show("Verifying selected OTA…");OtaPackage.verify(part);
   step="Saving verified OTA";if(!part.renameTo(ZIP))throw new IOException("Cannot save selected update");
   show("OTA verified. Ready to install.");
  }catch(Exception e){throw new IOException(step+": "+(e.getMessage()==null?e.getClass().getSimpleName():e.getMessage()),e);}
  finally{part.delete();}
 }
 private void openDownloadFolder(){
  Intent intent=new Intent(Intent.ACTION_VIEW,Uri.parse("https://drive.google.com/drive/folders/"+FOLDER+"?usp=sharing"));
  intent.addCategory(Intent.CATEGORY_BROWSABLE);
  try{startActivity(intent);show("Download the latest OTA from the folder, then return here and select its ZIP.");}
  catch(ActivityNotFoundException e){show("No app can open the download folder. Install a browser, or select an OTA ZIP downloaded elsewhere.");}
 }
 private void copy(InputStream in,OutputStream out)throws Exception{if(in==null)throw new IOException("Cannot open update");byte[] b=new byte[1024*1024];int n;long done=0,last=0;while((n=in.read(b))!=-1){done+=n;if(done>16L*1024*1024*1024)throw new IOException("Update exceeds size limit");out.write(b,0,n);if(System.currentTimeMillis()-last>1000){show("Reading OTA: "+done/1048576+" MiB");last=System.currentTimeMillis();}}}
 private void install()throws Exception{show("Verifying OTA before reboot…");OtaPackage.verify(ZIP);String id=Long.toString(System.currentTimeMillis());try(FileOutputStream out=new FileOutputStream(new File(REQUEST,"id"))){out.write(id.getBytes("UTF-8"));android.system.Os.fchmod(out.getFD(),0640);out.getFD().sync();}
  Class.forName("android.os.SystemProperties").getMethod("set",String.class,String.class).invoke(null,"sys.unica.ota.request",id);
  long end=System.currentTimeMillis()+600000;File response=new File("/data/misc/unica_ota/staged/response");while(System.currentTimeMillis()<end){if(response.exists()){String text;try(InputStream in=new FileInputStream(response)){text=new String(OtaPackage.read(in,4096),"UTF-8").trim();}if(text.startsWith(id+" ")){if(!text.equals(id+" ready"))throw new IOException(text.substring(id.length()+1));show("Rebooting into OrangeFox…");((PowerManager)getSystemService(POWER_SERVICE)).reboot("recovery");return;}}Thread.sleep(1000);}throw new IOException("Recovery staging timed out. Do not reboot until the request is checked.");
 }
}
