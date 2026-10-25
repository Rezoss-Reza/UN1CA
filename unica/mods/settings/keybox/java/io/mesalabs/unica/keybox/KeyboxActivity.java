/* Copyright (c) 2026 Rezoss
 * SPDX-License-Identifier: GPL-3.0-or-later */
package io.mesalabs.unica.keybox;

import android.app.*;
import android.content.*;
import android.graphics.Color;
import android.graphics.drawable.*;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.view.*;
import android.webkit.*;
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
    private static final String WRAPPER="https://unica-keybox.invalid/";
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final List<View> cards=new ArrayList<>();
    private LinearLayout content,card;
    private boolean busy;
    private String status="Check the current keybox against Google's revocation list.";
    private boolean red;
    private WebView browser;
    private WebMessagePort channel;
    private boolean selectionPending;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);setTitle("Hardware attestation · Keybox");
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        if(getActionBar()!=null)getActionBar().setDisplayHomeAsUpEnabled(true);
        draw();checkStatus();
    }
    @Override protected void onDestroy(){closeBrowser();worker.shutdownNow();super.onDestroy();}
    @Override public boolean onOptionsItemSelected(MenuItem item){if(item.getItemId()==android.R.id.home){onBackPressed();return true;}return super.onOptionsItemSelected(item);}
    @Override public void onBackPressed(){if(browser!=null){closeBrowser();draw();return;}if(busy){Toast.makeText(this,"Wait for the keybox operation to finish",Toast.LENGTH_SHORT).show();return;}super.onBackPressed();}
    private String current(){return Settings.Secure.getString(getContentResolver(),SETTING);}
    private boolean loaded(){String xml=current();return xml!=null&&!xml.trim().isEmpty();}
    private boolean enabled(){try{return (Boolean)Class.forName("android.os.SemSystemProperties").getMethod("getBoolean",String.class,boolean.class).invoke(null,"persist.sys.unica.keybox",false);}catch(Exception e){return false;}}
    private int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
    private void group(){card=new LinearLayout(this);card.setOrientation(1);card.setPadding(dp(20),dp(16),dp(20),dp(16));LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.bottomMargin=dp(16);content.addView(card,p);cards.add(card);}
    private TextView text(String value,int size){TextView v=new TextView(this);v.setText(value);v.setTextSize(size);v.setPadding(0,dp(8),0,dp(8));card.addView(v);return v;}
    private void button(String title,String summary,Runnable action){Button b=new Button(this);b.setText(title+"\n"+summary);b.setAllCaps(false);b.setGravity(Gravity.START|Gravity.CENTER_VERTICAL);b.setMinHeight(dp(64));b.setPadding(dp(12),dp(12),dp(12),dp(12));b.setEnabled(!busy);b.setOnClickListener(v->action.run());card.addView(b,new LinearLayout.LayoutParams(-1,-2));}
    private void draw(){
        if(isFinishing()||isDestroyed())return;
        setTitle("Hardware attestation · Keybox");
        cards.clear();ScrollView scroll=new ScrollView(this);content=new LinearLayout(this);content.setOrientation(1);content.setPadding(dp(12),dp(12),dp(12),dp(24));scroll.addView(content);setContentView(scroll);
        scroll.setOnApplyWindowInsetsListener((v,insets)->{android.graphics.Insets bars=insets.getInsets(WindowInsets.Type.systemBars());v.setPadding(bars.left,bars.top,bars.right,bars.bottom);return insets;});
        group();text("Current keybox",20);text(loaded()?"Keybox loaded · Spoof hardware attestation "+(enabled()?"on":"off"):"No keybox imported",16);
        TextView state=text(status,16);if(red)state.setTextColor(dark()?0xffff8a80:0xffb3261e);
        button(busy?"Checking…":"Check revocation status","Uses Google's current certificate status list",this::checkStatus);
        group();text("Install a keybox",20);
        button("Local","Import keybox.xml from storage",()->{Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE);startActivityForResult(intent,41);});
        button("AOSP","Public software test keybox from Android",()->new AlertDialog.Builder(this).setTitle("Apply AOSP keybox?").setMessage("This public software test keybox is not hardware-backed and does not provide Google hardware attestation or guarantee Play Integrity. Its test certificates may be expired. It replaces the current imported keybox.").setNegativeButton("Cancel",null).setPositiveButton("Review",(d,w)->stage(()->asset(),true,"AOSP")).show());
        button("Community repository","Browse Tricky Addon Keybox Repository",this::browse);
        group();text("Keybox data",20);text("Importing replaces the current keybox. The existing spoofing switch stays unchanged. Keep the original file if you may need to restore it; clearing can affect access to simulator-created keys after a restart.",14);
        button("Clear imported keybox","Remove the XML from Settings",()->new AlertDialog.Builder(this).setTitle("Clear imported keybox?").setMessage("Existing simulator-created keys may require the same keybox after a daemon restart or reboot.").setNegativeButton("Cancel",null).setPositiveButton("Clear",(d,w)->{if(!Settings.Secure.putString(getContentResolver(),SETTING,null)){failure("Could not clear keybox");return;}status="No keybox imported";red=false;draw();}).show());
        style();
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
        for(View v:cards){GradientDrawable shape=new GradientDrawable();shape.setColor(surface);shape.setCornerRadius(dp(28));v.setBackground(shape);}
    }
    private interface Job<T>{T run()throws Exception;}
    private interface Done<T>{void run(T value)throws Exception;}
    private <T> void background(Job<T> job,Done<T> done){
        if(busy)return;busy=true;draw();worker.submit(()->{try{T value=job.run();runOnUiThread(()->{busy=false;if(isDestroyed()||isFinishing())return;try{done.run(value);}catch(Exception e){failure("Keybox operation failed. Current data was not replaced.");}});}catch(Exception e){runOnUiThread(()->{busy=false;if(!isDestroyed())failure("Could not read or validate the keybox. Current data was not replaced.");});}});
    }
    private void failure(String message){status=message;red=false;draw();}
    private String readAsset(String name)throws Exception{try(InputStream in=getAssets().open("unica-keybox/"+name)){return new String(Keybox.read(in,Keybox.MAX_BYTES),StandardCharsets.UTF_8);}}
    private String asset()throws Exception{return readAsset("aosp-keybox.txt");}
    @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);if(request==41&&result==RESULT_OK&&data!=null&&data.getData()!=null){Uri uri=data.getData();stage(()->{try(InputStream in=getContentResolver().openInputStream(uri)){return new String(Keybox.read(in,Keybox.MAX_BYTES),StandardCharsets.UTF_8).replaceFirst("^\\uFEFF","");}},false,"Local");}}
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
    private void closeBrowser(){selectionPending=false;if(channel!=null){channel.close();channel=null;}if(browser!=null){browser.stopLoading();browser.destroy();browser=null;}}
    private void browse(){
        if(busy)return;setTitle("Keybox repository");browser=new WebView(this);WebView view=browser;
        view.setOnApplyWindowInsetsListener((v,insets)->{android.graphics.Insets bars=insets.getInsets(WindowInsets.Type.systemBars());v.setPadding(bars.left,bars.top,bars.right,bars.bottom);return insets;});
        WebSettings settings=view.getSettings();settings.setJavaScriptEnabled(true);settings.setDomStorageEnabled(true);settings.setAllowFileAccess(false);settings.setAllowContentAccess(false);settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);settings.setSupportMultipleWindows(false);
        view.setWebViewClient(new WebViewClient(){
            @Override public boolean shouldOverrideUrlLoading(WebView web,WebResourceRequest request){return request.isForMainFrame()||!"https".equals(request.getUrl().getScheme());}
            @Override public void onPageFinished(WebView web,String url){
                if(!WRAPPER.equals(url)||channel!=null)return;
                WebMessagePort[] ports=web.createWebMessageChannel();channel=ports[0];channel.setWebMessageCallback(new WebMessagePort.WebMessageCallback(){@Override public void onMessage(WebMessagePort port,WebMessage message){
                    try{JSONObject selection=new JSONObject(message.getData());if("error".equals(selection.optString("type"))){Toast.makeText(KeyboxActivity.this,"The repository could not download that keybox",Toast.LENGTH_LONG).show();return;}if(selectionPending||!"download".equals(selection.optString("type")))return;String address=selection.getString("url");URI uri=new URI(address);if(!"https".equals(uri.getScheme())||uri.getHost()==null)return;
                        selectionPending=true;
                        AlertDialog prompt=new AlertDialog.Builder(KeyboxActivity.this).setTitle("Download selected keybox?").setMessage("Source: "+uri.getHost()+"\nThe downloaded keybox will be validated before you can apply it.").setNegativeButton("Cancel",null).setPositiveButton("Download",(d,w)->{closeBrowser();stage(()->get(address,Keybox.MAX_BYTES),false,"community");}).create();prompt.setOnDismissListener(d->selectionPending=false);prompt.show();
                    }catch(Exception e){Toast.makeText(KeyboxActivity.this,"Repository selection unavailable",Toast.LENGTH_SHORT).show();}
                }});web.postWebMessage(new WebMessage("connect",new WebMessagePort[]{ports[1]}),Uri.parse(WRAPPER));
            }
        });
        // Only the local wrapper owns the message port. Remote frames never get a Java bridge.
        try{String html=readAsset("repository.html");setContentView(view);view.loadDataWithBaseURL(WRAPPER,html,"text/html","UTF-8",null);}catch(Exception e){closeBrowser();failure("Repository view unavailable");}
    }
}
