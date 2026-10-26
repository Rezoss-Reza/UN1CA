/* Copyright (c) 2026 Rezoss
 * SPDX-License-Identifier: GPL-3.0-or-later */
package io.mesalabs.unica.keybox;

import android.app.*;
import android.content.*;
import android.graphics.Color;
import android.graphics.drawable.*;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;

/** Native Settings owner of all keybox writes. The repository receives no imported data. */
public final class KeyboxActivity extends Activity {
    private static final String SETTING="unica_keybox_data";
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final List<View> cards=new ArrayList<>();
    private LinearLayout content,card;
    private ScrollView scrollView;
    private boolean busy;
    private String status="Check the current keybox against Google's revocation list.";
    private boolean red;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);setTitle("Hardware attestation · Keybox");
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        if(getActionBar()!=null)getActionBar().setDisplayHomeAsUpEnabled(true);
        draw();checkStatus();
    }
    @Override protected void onDestroy(){worker.shutdownNow();super.onDestroy();}
    @Override public boolean onOptionsItemSelected(MenuItem item){if(item.getItemId()==android.R.id.home){onBackPressed();return true;}return super.onOptionsItemSelected(item);}
    @Override public void onBackPressed(){if(busy){Toast.makeText(this,"Wait for the keybox operation to finish",Toast.LENGTH_SHORT).show();return;}super.onBackPressed();}
    private String current(){return Settings.Secure.getString(getContentResolver(),SETTING);}
    private boolean loaded(){String xml=current();return xml!=null&&!xml.trim().isEmpty();}
    private boolean enabled(){try{return (Boolean)Class.forName("android.os.SemSystemProperties").getMethod("getBoolean",String.class,boolean.class).invoke(null,"persist.sys.unica.keybox",false);}catch(Exception e){return false;}}
    private int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
    private int themed(int attribute,int fallback){TypedValue value=new TypedValue();if(!getTheme().resolveAttribute(attribute,value,true))return fallback;return value.resourceId!=0?getColor(value.resourceId):value.data;}
    private TextView label(String value,int size,boolean secondary){
        TextView view=new TextView(this);view.setText(value);view.setTextSize(size);
        view.setTextColor(themed(secondary?android.R.attr.textColorSecondary:android.R.attr.textColorPrimary,secondary?(dark()?0xffbfc1c7:0xff62656b):(dark()?0xfff4f4f6:0xff17181b)));
        view.setGravity(Gravity.START);return view;
    }
    private void group(){card=new LinearLayout(this);card.setOrientation(1);card.setClipToOutline(true);LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.bottomMargin=dp(24);content.addView(card,p);cards.add(card);}
    private TextView text(String value,int size){TextView v=label(value,size,size<20);v.setPaddingRelative(dp(24),size>=20?dp(22):0,dp(24),dp(14));if(size>=20)v.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));card.addView(v);return v;}
    private void note(String value){TextView view=label(value,14,true);view.setPaddingRelative(dp(24),0,dp(24),dp(24));content.addView(view);}
    private void button(String title,String summary,Runnable action){
        if(card.getChildCount()>0){View divider=new View(this);divider.setBackgroundColor(themed(android.R.attr.textColorSecondary,dark()?0xffbfc1c7:0xff62656b));divider.setAlpha(0.14f);LinearLayout.LayoutParams line=new LinearLayout.LayoutParams(-1,dp(1));line.setMarginStart(dp(24));line.setMarginEnd(dp(24));card.addView(divider,line);}
        LinearLayout row=new LinearLayout(this);row.setOrientation(LinearLayout.HORIZONTAL);row.setGravity(Gravity.CENTER_VERTICAL);row.setMinimumHeight(dp(88));row.setPaddingRelative(dp(24),dp(18),dp(20),dp(18));
        LinearLayout labels=new LinearLayout(this);labels.setOrientation(LinearLayout.VERTICAL);
        TextView heading=label(title,20,false);heading.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));labels.addView(heading);
        if(title.equals("Clear imported keybox"))heading.setTextColor(dark()?0xffff8a80:0xffb3261e);
        TextView detail=label(summary,15,true);detail.setPadding(0,dp(5),0,0);labels.addView(detail);
        row.addView(labels,new LinearLayout.LayoutParams(0,-2,1));
        TextView arrow=label(getResources().getConfiguration().getLayoutDirection()==View.LAYOUT_DIRECTION_RTL?"‹":"›",26,true);arrow.setPaddingRelative(dp(16),0,0,0);arrow.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);row.addView(arrow);
        row.setBackground(new RippleDrawable(ColorStateList.valueOf(themed(android.R.attr.colorControlHighlight,dark()?0x33ffffff:0x18000000)),null,new ColorDrawable(Color.WHITE)));
        row.setEnabled(!busy);row.setAlpha(busy?0.45f:1f);row.setFocusable(true);row.setContentDescription(title+". "+summary);row.setOnClickListener(v->action.run());
        labels.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        row.setAccessibilityDelegate(new View.AccessibilityDelegate(){@Override public void onInitializeAccessibilityNodeInfo(View host,android.view.accessibility.AccessibilityNodeInfo info){super.onInitializeAccessibilityNodeInfo(host,info);info.setClassName(Button.class.getName());}});
        card.addView(row,new LinearLayout.LayoutParams(-1,-2));
    }
    private void draw(){
        if(isFinishing()||isDestroyed())return;
        setTitle("Keybox");
        int previousScroll=scrollView==null?0:scrollView.getScrollY();
        cards.clear();ScrollView scroll=new ScrollView(this);scrollView=scroll;scroll.setFillViewport(true);scroll.setClipToPadding(false);content=new LinearLayout(this);content.setOrientation(1);content.setPadding(dp(16),dp(20),dp(16),dp(24));scroll.addView(content);setContentView(scroll);
        scroll.setOnApplyWindowInsetsListener((v,insets)->{android.graphics.Insets bars=insets.getInsets(WindowInsets.Type.systemBars());v.setPadding(bars.left,bars.top,bars.right,bars.bottom);return insets;});
        group();text("Current keybox",22);text(loaded()?"Keybox loaded · Spoof hardware attestation "+(enabled()?"on":"off"):"No keybox imported",16);
        TextView state=text(status,16);if(red)state.setTextColor(dark()?0xffff8a80:0xffb3261e);
        button(busy?"Checking…":"Check revocation status","Uses Google's current certificate status list",this::checkStatus);
        group();text("Install a keybox",22);
        button("Local","Import keybox.xml from storage",()->{Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE);startActivityForResult(intent,41);});
        button("AOSP","Public software test keybox from Android",()->new AlertDialog.Builder(this).setTitle("Apply AOSP keybox?").setMessage("This public software test keybox is not hardware-backed and does not provide Google hardware attestation or guarantee Play Integrity. Its test certificates may be expired. It replaces the current imported keybox.").setNegativeButton("Cancel",null).setPositiveButton("Review",(d,w)->stage(()->asset(),true,"AOSP")).show());
        button("Community repository","Browse Tricky Addon Keybox Repository",this::browse);
        note("Importing replaces the current keybox. The existing spoofing switch stays unchanged. Keep the original file if you may need to restore it; clearing can affect access to simulator-created keys after a restart.");
        group();text("Keybox data",22);
        button("Clear imported keybox","Remove the XML from Settings",()->new AlertDialog.Builder(this).setTitle("Clear imported keybox?").setMessage("Existing simulator-created keys may require the same keybox after a daemon restart or reboot.").setNegativeButton("Cancel",null).setPositiveButton("Clear",(d,w)->{if(!Settings.Secure.putString(getContentResolver(),SETTING,null)){failure("Could not clear keybox");return;}status="No keybox imported";red=false;draw();}).show());
        style();
        scroll.post(()->scroll.scrollTo(0,previousScroll));
    }
    private boolean dark(){return (getResources().getConfiguration().uiMode&0x30)==0x20;}
    private int color(String name,int fallback){int id=getResources().getIdentifier(name,"color",getPackageName());return id==0?fallback:getColor(id);}
    private void style(){
        boolean transparent=Settings.System.getInt(getContentResolver(),"unica_settings_bg_transparency_enabled",0)!=0;
        int level=Math.max(70,Math.min(100,Settings.System.getInt(getContentResolver(),"unica_settings_bg_transparency_level",70)));
        int surface=color("sesl_background_color_"+(dark()?"dark":"light"),dark()?0xff17171a:0xffffffff);
        int background=color("sesl_round_and_bgcolor_"+(dark()?"dark":"light"),dark()?0xff000000:0xfff2f2f2);
        if(transparent){surface=(surface&0xffffff)|((255-level*255/100)<<24);background=0;getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER);}else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER);
        getWindow().setFormat(transparent?android.graphics.PixelFormat.TRANSLUCENT:android.graphics.PixelFormat.OPAQUE);getWindow().setBackgroundDrawable(new ColorDrawable(background));
        for(View v:cards){GradientDrawable shape=new GradientDrawable();shape.setColor(surface);shape.setCornerRadius(dp(30));v.setBackground(shape);}
    }
    private interface Job<T>{T run()throws Exception;}
    private interface Done<T>{void run(T value)throws Exception;}
    private <T> void background(Job<T> job,Done<T> done){
        if(busy)return;busy=true;draw();worker.submit(()->{try{T value=job.run();runOnUiThread(()->{busy=false;if(isDestroyed()||isFinishing())return;try{done.run(value);}catch(Exception e){failure("Keybox operation failed. Current data was not replaced.");}});}catch(Exception e){runOnUiThread(()->{busy=false;if(!isDestroyed())failure("Could not read or validate the keybox. Current data was not replaced.");});}});
    }
    private void failure(String message){status=message;red=false;draw();}
    private String readAsset(String name)throws Exception{try(InputStream in=getAssets().open("unica-keybox/"+name)){return new String(Keybox.read(in,Keybox.MAX_BYTES),StandardCharsets.UTF_8);}}
    private String asset()throws Exception{return readAsset("aosp-keybox.txt");}
    @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);if(request==42&&result==RESULT_OK&&data!=null){String address=data.getStringExtra("url");try{URI uri=new URI(address);if(address.length()>4096||!"https".equals(uri.getScheme())||uri.getHost()==null||uri.getUserInfo()!=null)throw new IllegalArgumentException();new AlertDialog.Builder(this).setTitle("Download selected keybox?").setMessage("Source: "+uri.getHost()+"\nThe downloaded keybox will be validated before you can apply it.").setNegativeButton("Cancel",null).setPositiveButton("Download",(d,w)->stage(()->get(address,Keybox.MAX_BYTES),false,"community")).show();}catch(Exception e){failure("Repository selection unavailable");}return;}if(request==41&&result==RESULT_OK&&data!=null&&data.getData()!=null){Uri uri=data.getData();stage(()->{try(InputStream in=getContentResolver().openInputStream(uri)){return new String(Keybox.read(in,Keybox.MAX_BYTES),StandardCharsets.UTF_8).replaceFirst("^\\uFEFF","");}},false,"Local");}}
    private static final class Candidate {String xml,summary;boolean aosp,revoked,unknown;}
    private void stage(Job<String> source,boolean aosp,String label){
        background(()->{
            Candidate c=new Candidate();c.xml=source.run().replaceFirst("^\\uFEFF","");c.aosp=aosp;Keybox parsed=Keybox.parse(c.xml,aosp);
            if(parsed.expired&&!aosp)throw new IllegalArgumentException("Expired certificate");
            try{c.revoked=parsed.revoked(revocations());}catch(Exception e){c.unknown=true;}
            c.summary=aosp?"AOSP software test keybox"+(parsed.expired?" · expired test certificate":""):c.revoked?"Revoked — listed by Google":c.unknown?"Revocation status unknown — could not fetch Google's list":"Not listed as revoked by Google";
            return c;
        },c->{
            TextView warning=new TextView(this);warning.setPadding(dp(24),dp(16),dp(24),dp(16));warning.setText(c.summary+"\n\nReplace the current imported keybox with this "+label+" keybox?");if(c.revoked)warning.setTextColor(dark()?0xffff8a80:0xffb3261e);
            AlertDialog dialog=new AlertDialog.Builder(this).setTitle("Review keybox").setView(warning).setNegativeButton("Cancel",null).setPositiveButton(c.revoked?"Apply revoked keybox":"Apply",(d,w)->{
                if(!Settings.Secure.putString(getContentResolver(),SETTING,c.xml)){failure("Could not save keybox");return;}
                status=c.summary;red=c.revoked;draw();
            }).create();dialog.show();
        });
    }
    private void checkStatus(){
        String snapshot=current();if(snapshot==null||snapshot.trim().isEmpty()){status="No keybox imported";red=false;draw();return;}
        status="Checking certificate and revocation status…";red=false;
        background(()->{
            boolean aosp=snapshot.equals(asset());Keybox box=Keybox.parse(snapshot,aosp);
            Candidate c=new Candidate();c.aosp=aosp;
            try{c.revoked=box.revoked(revocations());}catch(Exception e){c.unknown=true;}
            c.summary=c.revoked?"Revoked — listed by Google":aosp?"AOSP software test keybox"+(box.expired?" · expired test certificate":"")+(c.unknown?"\nRevocation status unknown":"\nNot listed as revoked by Google"):box.expired?"Certificate expired or not yet valid":c.unknown?"Unknown — Google's revocation list is unavailable":"Not listed as revoked by Google";
            return c;
        },c->{if(!Objects.equals(snapshot,current())){status="Keybox changed. Check its status again.";red=false;}else{status=c.summary;red=c.revoked;}draw();});
    }
    private static Set<String> revocations()throws Exception{
        JSONObject entries=new JSONObject(get("https://android.googleapis.com/attestation/status",8*1024*1024)).getJSONObject("entries");
        Set<String> revoked=new HashSet<>();Iterator<String> keys=entries.keys();
        while(keys.hasNext()){String serial=keys.next();String state=entries.getJSONObject(serial).getString("status");if(!state.equals("REVOKED")&&!state.equals("SUSPENDED"))throw new IOException("Unknown certificate status");revoked.add(new java.math.BigInteger(serial,16).toString(16));}
        return revoked;
    }
    private static String get(String address,int limit)throws Exception{
        for(int redirects=0;redirects<4;redirects++){
            URI uri=new URI(address);if(!"https".equals(uri.getScheme())||uri.getHost()==null||uri.getUserInfo()!=null||(uri.getPort()!=-1&&uri.getPort()!=443))throw new IOException("HTTPS required");
            // Repository selections cannot turn a privileged fetch into a local-network request.
            for(InetAddress ip:InetAddress.getAllByName(uri.getHost()))if(ip.isAnyLocalAddress()||ip.isLoopbackAddress()||ip.isLinkLocalAddress()||ip.isSiteLocalAddress()||ip.isMulticastAddress()||(ip.getAddress().length==16&&(ip.getAddress()[0]&0xfe)==0xfc))throw new IOException("Public host required");
            HttpURLConnection connection=(HttpURLConnection)uri.toURL().openConnection();connection.setConnectTimeout(8000);connection.setReadTimeout(8000);connection.setInstanceFollowRedirects(false);connection.setRequestProperty("User-Agent","UN1CA Settings keybox");
            try{int code=connection.getResponseCode();if(code>=300&&code<=399){String next=connection.getHeaderField("Location");if(next==null)throw new IOException("Invalid redirect");address=uri.resolve(next).toString();continue;}if(code!=200)throw new IOException("Download failed");try(InputStream input=connection.getInputStream()){return new String(Keybox.read(input,limit),StandardCharsets.UTF_8);}}finally{connection.disconnect();}
        }
        throw new IOException("Too many redirects");
    }
    private void browse(){
        if(busy)return;
        // WebView is prohibited in Settings' system UID, including named processes.
        // Only this normal-UID browser returns a URL; XML validation and writes stay here.
        try{startActivityForResult(new Intent().setClassName("io.mesalabs.unica.keybox.browser","io.mesalabs.unica.keybox.browser.RepositoryActivity"),42);}
        catch(ActivityNotFoundException|SecurityException e){
            // A copied APK may not be registered by PackageManager. Keep browsing usable.
            try{startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse("https://keybox.kowx712.cc/")).addCategory(Intent.CATEGORY_BROWSABLE));Toast.makeText(this,"Download a keybox from the repository, then import it with Local.",Toast.LENGTH_LONG).show();}
            catch(ActivityNotFoundException|SecurityException unavailable){failure("No web browser is available to open https://keybox.kowx712.cc/");}
        }
    }
}
