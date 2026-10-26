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
    private String page="main",logSeverity="V",logArea="All",logQuery="",exportSnapshot;
    private int profileIndex;
    private List<String> logRows=new ArrayList<>();
    private TextView backendView;
    private final Handler refreshHandler=new Handler(Looper.getMainLooper());
    private final Runnable refreshStatus=new Runnable(){public void run(){if(backendView!=null)backendView.setText(backendSummary());refreshHandler.postDelayed(this,3000);}};
    @Override protected void onResume(){super.onResume();refreshHandler.post(refreshStatus);}
    @Override protected void onPause(){refreshHandler.removeCallbacks(refreshStatus);super.onPause();}
    private String status="Check the current keybox against Google's revocation list.";
    private boolean red;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);setTitle("Manage Keybox");
        if(state!=null){page=state.getString("page","main");profileIndex=state.getInt("profile",0);}
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        if(getActionBar()!=null)getActionBar().setDisplayHomeAsUpEnabled(true);
        draw();checkStatus();
    }
    @Override public void onSaveInstanceState(Bundle state){super.onSaveInstanceState(state);state.putString("page",page);state.putInt("profile",profileIndex);}
    @Override protected void onDestroy(){worker.shutdownNow();super.onDestroy();}
    @Override public boolean onOptionsItemSelected(MenuItem item){if(item.getItemId()==android.R.id.home){onBackPressed();return true;}return super.onOptionsItemSelected(item);}
    @Override public void onBackPressed(){if(busy){Toast.makeText(this,"Wait for the keybox operation to finish",Toast.LENGTH_SHORT).show();return;}if(!page.equals("main")){page=page.equals("profile")?"profiles":"main";profileIndex=0;scrollView=null;draw();return;}super.onBackPressed();}
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
        setTitle(page.equals("main")?"Manage Keybox":page.equals("profiles")?"Advanced profiles":page.equals("profile")?"Profile settings":"Diagnostics");
        backendView=null;
        int previousScroll=scrollView==null?0:scrollView.getScrollY();
        cards.clear();ScrollView scroll=new ScrollView(this);scrollView=scroll;scroll.setFillViewport(true);scroll.setClipToPadding(false);content=new LinearLayout(this);content.setOrientation(1);content.setPadding(dp(16),dp(20),dp(16),dp(24));scroll.addView(content);setContentView(scroll);
        scroll.setOnApplyWindowInsetsListener((v,insets)->{android.graphics.Insets bars=insets.getInsets(WindowInsets.Type.systemBars());v.setPadding(bars.left,bars.top,bars.right,bars.bottom);return insets;});
        if(!page.equals("main")){
            try{if(page.equals("profiles"))drawProfiles();else if(page.equals("profile"))drawProfile();else drawDiagnostics();}
            catch(Exception error){group();text("Configuration unavailable",22);text("Restore default routing to recover. Your current imported keybox is retained.",16);button("Restore default routing","Back up existing configuration and use the current keybox",this::restore);}
            style();scroll.post(()->scroll.scrollTo(0,previousScroll));return;
        }
        group();text("Backend status",22);backendView=text(backendSummary(),16);
        button("Refresh status","Shows live backend acknowledgement and connection separately",()->draw());
        if(fresh()&&property("sys.unica.teesim.config_error").equals("1"))text("Configuration rejected. New interception is disabled until the configuration is corrected.",16);
        group();text("Current keybox",22);text(loaded()?"Keybox loaded · Spoof hardware attestation "+(enabled()?"on":"off"):"No keybox imported",16);
        TextView state=text(status,16);if(red)state.setTextColor(dark()?0xffff8a80:0xffb3261e);
        button(busy?"Checking…":"Check revocation status","Uses Google's current certificate status list",this::checkStatus);
        button("Keybox details","Algorithms, chains, validity, signatures, root authority and revocation",()->{String xml=current();if(xml==null||xml.trim().isEmpty())message("Keybox details","No keybox imported.");else showDetailsXml(xml);});
        group();text("Attestation routing",22);
        button("Target apps","Choose apps for the first profile · shared UIDs stay together",()->{profileIndex=0;targets();});
        button("Advanced profiles","Multiple keyboxes and per-profile attestation settings",this::profiles);
        button("Diagnostics","Filter backend events and export redacted summaries",this::diagnostics);
        group();text("Install a keybox",22);
        button("Local","Import keybox.xml from storage",()->{Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE);startActivityForResult(intent,41);});
        button("AOSP","Public software test keybox from Android",()->new AlertDialog.Builder(this).setTitle("Apply AOSP keybox?").setMessage("This public software test keybox is not hardware-backed and does not provide Google hardware attestation or guarantee Play Integrity. Its test certificates may be expired. It replaces the current imported keybox.").setNegativeButton("Cancel",null).setPositiveButton("Review",(d,w)->stage(()->asset(),true,"AOSP")).show());
        button("Community repository","Browse Tricky Addon Keybox Repository",this::browse);
        note("Importing replaces the current keybox. The existing spoofing switch stays unchanged. Keep the original file if you may need to restore it; clearing can affect access to simulator-created keys after a restart.");
        group();text("Keybox data",22);
        button("Clear imported keybox","Remove the XML from Settings",()->new AlertDialog.Builder(this).setTitle("Clear imported keybox?").setMessage("Existing simulator-created keys may require the same keybox after a daemon restart or reboot.").setNegativeButton("Cancel",null).setPositiveButton("Clear",(d,w)->{if(!Settings.Secure.putString(getContentResolver(),SETTING,null)){failure("Could not clear keybox");return;}status="No keybox imported";red=false;draw();}).show());
        button("Restore default routing","Back up profiles; use the current keybox for owner-user apps",this::restore);
        button("Restore previous profiles","Undo the last restore of default routing",this::restoreBackup);
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
        if(busy)return;busy=true;draw();worker.submit(()->{try{T value=job.run();runOnUiThread(()->{busy=false;if(isDestroyed()||isFinishing())return;try{done.run(value);}catch(Exception e){failure("Operation failed. Current data was not replaced.");}});}catch(Exception e){runOnUiThread(()->{busy=false;if(!isDestroyed())failure("Operation failed. Current data was not replaced.");});}});
    }
    private void failure(String message){status=message;red=false;draw();}
    private String readAsset(String name)throws Exception{try(InputStream in=getAssets().open("unica-keybox/"+name)){return new String(Keybox.read(in,Keybox.MAX_BYTES),StandardCharsets.UTF_8);}}
    private String asset()throws Exception{return readAsset("aosp-keybox.txt");}
    @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);
        if(request==44){if(result==RESULT_OK&&data!=null&&data.getData()!=null&&exportSnapshot!=null){String snapshot=exportSnapshot;exportSnapshot=null;background(()->{try(OutputStream out=getContentResolver().openOutputStream(data.getData(),"wt")){if(out==null)throw new IOException();out.write(snapshot.getBytes(StandardCharsets.UTF_8));}return true;},ok->{draw();Toast.makeText(this,"Redacted diagnostics saved",Toast.LENGTH_SHORT).show();});}else exportSnapshot=null;return;}
        if(request==43){if(result==RESULT_OK&&data!=null&&data.getData()!=null)additional(data.getData());return;}
        if(request==42&&result==RESULT_OK&&data!=null){String address=data.getStringExtra("url");try{URI uri=new URI(address);if(address.length()>4096||!"https".equals(uri.getScheme())||uri.getHost()==null||uri.getUserInfo()!=null)throw new IllegalArgumentException();new AlertDialog.Builder(this).setTitle("Download selected keybox?").setMessage("Source: "+uri.getHost()+"\nThe downloaded keybox will be validated before you can apply it.").setNegativeButton("Cancel",null).setPositiveButton("Download",(d,w)->stage(()->get(address,Keybox.MAX_BYTES),false,"community")).show();}catch(Exception e){failure("Repository selection unavailable");}return;}if(request==41&&result==RESULT_OK&&data!=null&&data.getData()!=null){Uri uri=data.getData();stage(()->{try(InputStream in=getContentResolver().openInputStream(uri)){return new String(Keybox.read(in,Keybox.MAX_BYTES),StandardCharsets.UTF_8).replaceFirst("^\\uFEFF","");}},false,"Local");}}
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
    private String property(String name){try{return (String)Class.forName("android.os.SemSystemProperties").getMethod("get",String.class,String.class).invoke(null,name,"");}catch(Exception e){return "";}}
    private boolean fresh(){try{long age=SystemClock.elapsedRealtime()-Long.parseLong(property("sys.unica.teesim.heartbeat"));return age>=0&&age<12000;}catch(Exception e){return false;}}
    private String backend(String name){return fresh()?(property("sys.unica.teesim."+name).equals("1")?"Yes":"No"):"Unavailable — backend not reporting";}
    private String backendSummary(){return "Enabled: "+(enabled()?"Yes":"No")+"\nKeybox loaded by interceptor: "+backend("loaded")+"\nConfiguration acknowledged: "+backend("ack")+"\nInterceptor connected: "+backend("connected");}
    private JSONObject config()throws JSONException{return Profiles.load(Settings.Secure.getString(getContentResolver(),Profiles.SETTING));}
    private JSONObject selected(JSONObject root)throws JSONException{return root.getJSONArray("profiles").getJSONObject(profileIndex);}
    private void save(JSONObject root)throws Exception{
        Profiles.validate(root);
        // A shared UID is a single keystore caller, even if PackageManager lists several packages.
        Map<Integer,Integer> owners=new HashMap<>();JSONArray profiles=root.getJSONArray("profiles");
        for(int i=0;i<profiles.length();i++){JSONArray apps=profiles.getJSONObject(i).getJSONArray("apps");for(int n=0;n<apps.length();n++){
            try{int uid=getPackageManager().getApplicationInfo(apps.getString(n),0).uid;
                if(uid<10000||uid>19999)throw new IllegalArgumentException("Only owner-user application UIDs can be targeted");
                Integer old=owners.put(uid,i);if(old!=null&&old!=i)throw new IllegalArgumentException("Apps sharing a UID must use the same profile");
            }catch(android.content.pm.PackageManager.NameNotFoundException absent){/* Retain selections for temporarily uninstalled apps. */}
        }}
        if(!Settings.Secure.putString(getContentResolver(),Profiles.SETTING,root.toString()))throw new IOException("Could not save configuration");
        Toast.makeText(this,"Saved · waiting for backend acknowledgement",Toast.LENGTH_SHORT).show();draw();
    }
    private void changed(JSONObject root){try{save(root);}catch(Exception e){message("Configuration not saved",e instanceof JSONException||e instanceof IllegalArgumentException?e.getMessage():"Could not save configuration. Existing settings remain active.");}}
    private void message(String title,String value){TextView view=label(value,16,false);view.setTextIsSelectable(true);view.setPadding(dp(24),dp(20),dp(24),dp(20));ScrollView scroll=new ScrollView(this);scroll.addView(view);new AlertDialog.Builder(this).setTitle(title).setView(scroll).setPositiveButton("Close",null).show();}
    private interface ValueAction {void run(String value)throws Exception;}
    private void edit(String title,String initial,String hint,ValueAction action){
        EditText input=new EditText(this);input.setSingleLine(true);input.setText(initial);input.setHint(hint);input.setPadding(dp(24),dp(18),dp(24),dp(18));
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle(title).setView(input).setNegativeButton("Cancel",null).setPositiveButton("Save",null).create();
        dialog.setOnShowListener(d->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{try{action.run(input.getText().toString().trim());dialog.dismiss();}catch(Exception e){input.setError(e instanceof JSONException||e instanceof IllegalArgumentException?e.getMessage():"Could not save");}}));dialog.show();
    }
    private void details(){
        try{JSONObject root=config();String xml=Profiles.xml(root,selected(root),current());
            if(xml==null||xml.trim().isEmpty()){message("Keybox details","No keybox configured for this profile.");return;}
            showDetailsXml(xml);
        }catch(Exception e){message("Keybox details","Configuration or certificate validation failed. No key material was changed.");}
    }
    private void showDetailsXml(String xml){
            background(()->{
                boolean aosp=xml.equals(asset());Keybox box=Keybox.parse(xml,aosp);Set<String> revoked=null;
                try{revoked=revocations();}catch(Exception unavailable){}
                StringBuilder out=new StringBuilder();java.text.DateFormat dates=java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM,java.text.DateFormat.SHORT);
                int chainNumber=0;
                for(List<java.security.cert.X509Certificate> chain:box.chains){
                    out.append("Chain ").append(++chainNumber).append("\nAlgorithm: ").append(chain.get(0).getPublicKey().getAlgorithm()).append("\nChain length: ").append(chain.size())
                       .append("\nSignatures: verified\nPrivate key match: verified\nRoot authority: ").append(aosp?"AOSP software test root":"Google attestation root").append('\n');
                    for(int i=0;i<chain.size();i++){java.security.cert.X509Certificate cert=chain.get(i);boolean valid=true;try{cert.checkValidity();}catch(Exception e){valid=false;}
                        out.append("\nCertificate ").append(i+1).append("\nValid from: ").append(dates.format(cert.getNotBefore())).append("\nValid until: ").append(dates.format(cert.getNotAfter()))
                           .append("\nValidity: ").append(valid?"Current":"Expired or not yet valid").append("\nRevocation: ").append(revoked==null?"Unknown — list unavailable":revoked.contains(cert.getSerialNumber().toString(16))?"Revoked or suspended":"Not listed").append('\n');
                    }out.append('\n');
                }
                return out.toString();
            },value->{draw();message("Keybox details",value);});
    }
    private static final class AppRow {int uid;String label;List<String> packages=new ArrayList<>();String title(){return label+(packages.size()>1?" · shared UID":"");}}
    private void targets(){
        try{JSONObject root=config();JSONObject p=selected(root);
            background(()->{
                Map<Integer,AppRow> grouped=new TreeMap<>();
                for(android.content.pm.ApplicationInfo info:getPackageManager().getInstalledApplications(0)){
                    if(info.uid<10000||info.uid>19999)continue;
                    AppRow row=grouped.get(info.uid);if(row==null){row=new AppRow();row.uid=info.uid;row.label=getPackageManager().getApplicationLabel(info).toString();grouped.put(info.uid,row);}row.packages.add(info.packageName);
                }
                List<AppRow> rows=new ArrayList<>(grouped.values());for(AppRow row:rows)Collections.sort(row.packages);
                Collections.sort(rows,(a,b)->a.label.compareToIgnoreCase(b.label));return rows;
            },rows->{draw();
                Set<String> chosen=new HashSet<>();JSONArray apps=p.getJSONArray("apps");for(int i=0;i<apps.length();i++)chosen.add(apps.getString(i));
                LinearLayout layout=new LinearLayout(this);layout.setOrientation(1);layout.setPadding(dp(16),dp(8),dp(16),0);
                Switch all=new Switch(this);all.setText("All remaining owner-user apps");all.setPadding(dp(8),dp(12),dp(8),dp(12));all.setChecked(p.getString("scope").equals("all"));layout.addView(all);
                TextView help=label("Shared-UID packages are selected together. Apps assigned to another profile take priority over ‘all remaining’.",14,true);help.setPadding(dp(8),dp(8),dp(8),dp(12));layout.addView(help);
                EditText search=new EditText(this);search.setSingleLine(true);search.setHint("Search apps or packages");layout.addView(search);
                ListView list=new ListView(this);list.setChoiceMode(ListView.CHOICE_MODE_MULTIPLE);layout.addView(list,new LinearLayout.LayoutParams(-1,dp(300)));
                List<AppRow> visible=new ArrayList<>();
                Runnable refresh=()->{visible.clear();String query=search.getText().toString().toLowerCase(Locale.ROOT);List<String> labels=new ArrayList<>();for(AppRow row:rows)if((row.label+" "+row.packages.toString()).toLowerCase(Locale.ROOT).contains(query)){visible.add(row);labels.add(row.title()+"\n"+String.join(", ",row.packages));}
                    list.setAdapter(new ArrayAdapter<String>(this,android.R.layout.simple_list_item_multiple_choice,labels));for(int i=0;i<visible.size();i++){boolean picked=false;for(String pkg:visible.get(i).packages)if(chosen.contains(pkg))picked=true;list.setItemChecked(i,picked);}list.setEnabled(!all.isChecked());list.setAlpha(all.isChecked()?0.45f:1f);};
                list.setOnItemClickListener((parent,view,pos,id)->{AppRow row=visible.get(pos);if(list.isItemChecked(pos))chosen.addAll(row.packages);else chosen.removeAll(row.packages);});
                all.setOnCheckedChangeListener((v,on)->refresh.run());search.addTextChangedListener(new android.text.TextWatcher(){public void beforeTextChanged(CharSequence s,int start,int count,int after){}public void onTextChanged(CharSequence s,int start,int before,int count){refresh.run();}public void afterTextChanged(android.text.Editable s){}});refresh.run();
                AlertDialog dialog=new AlertDialog.Builder(this).setTitle("Target apps").setView(layout).setNegativeButton("Cancel",null).setPositiveButton("Save",null).create();
                dialog.setOnShowListener(d->dialog.getButton(-1).setOnClickListener(v->{try{p.put("scope",all.isChecked()?"all":"selected");p.put("apps",new JSONArray(all.isChecked()?Collections.emptyList():new TreeSet<>(chosen)));save(root);dialog.dismiss();}catch(Exception e){help.setText(e instanceof JSONException||e instanceof IllegalArgumentException?e.getMessage():"Could not save target apps");}}));dialog.show();
            });
        }catch(Exception e){message("Target apps","Saved configuration cannot be read. Restore default configuration to recover.");}
    }
    private void profiles(){page="profiles";scrollView=null;draw();}
    private void drawProfiles()throws Exception{
        JSONObject root=config();JSONArray profiles=root.getJSONArray("profiles");
        group();text("Advanced profiles",22);text("Each profile has its own keybox, target apps and attestation settings.",16);
        for(int i=0;i<profiles.length();i++){final int index=i;JSONObject p=profiles.getJSONObject(i);button(p.getString("id"),p.getString("keybox")+" · "+p.optString("mode","patch"),()->{profileIndex=index;page="profile";scrollView=null;draw();});}
        button("Add profile","Start with no target apps",()->edit("Profile name","","Letters, numbers, dash or underscore",name->{JSONObject next=config();next.getJSONArray("profiles").put(Profiles.profile(name));save(next);}));
        group();text("Saved keyboxes",22);JSONObject boxes=root.getJSONObject("keyboxes");
        if(boxes.length()==0)text("No additional keyboxes saved",16);
        for(Iterator<String> it=boxes.keys();it.hasNext();){String name=it.next();button(name,"Manage this saved keybox",()->manageBox(name));}
        button("Save current keybox","Keep a named copy for use by profiles",()->edit("Save current keybox","","Unique name",name->{JSONObject next=config();if(current()==null)throw new IllegalArgumentException("Import a keybox first");if(next.getJSONObject("keyboxes").has(name))throw new IllegalArgumentException("Name already exists");next.getJSONObject("keyboxes").put(name,current());save(next);}));
        button("Import additional keybox","Validate a local XML file without replacing the current keybox",()->{Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE);startActivityForResult(intent,43);});
        note("Keep original keybox files. Removing or replacing a signer can affect simulator-created keys after a restart.");
    }
    private void manageBox(String name){
        new AlertDialog.Builder(this).setTitle(name).setItems(new String[]{"Keybox details","Rename","Delete"},(dialog,which)->{
            if(which==0){try{JSONObject root=config();
                inspectSavedXml(root.getJSONObject("keyboxes").getString(name));}catch(Exception e){message("Keybox details","Could not read saved keybox");}}
            else if(which==1)edit("Rename keybox",name,"Unique name",value->{JSONObject root=config();JSONObject boxes=root.getJSONObject("keyboxes");if(value.equals(name))return;if(boxes.has(value))throw new IllegalArgumentException("Name already exists");String xml=boxes.getString(name);boxes.remove(name);boxes.put(value,xml);JSONArray profiles=root.getJSONArray("profiles");for(int i=0;i<profiles.length();i++){JSONObject p=profiles.getJSONObject(i);if(p.getString("keybox").equals(name))p.put("keybox",value);}save(root);});
            else new AlertDialog.Builder(this).setTitle("Delete saved keybox?").setMessage("Profiles still using this keybox must be changed first. Keep its original XML file to preserve access to existing keys.").setNegativeButton("Cancel",null).setPositiveButton("Delete",(d,w)->{try{JSONObject root=config();JSONArray profiles=root.getJSONArray("profiles");for(int i=0;i<profiles.length();i++)if(profiles.getJSONObject(i).getString("keybox").equals(name))throw new IllegalArgumentException("This keybox is still used by a profile");root.getJSONObject("keyboxes").remove(name);save(root);}catch(Exception e){message("Keybox retained",e instanceof IllegalArgumentException?e.getMessage():"Could not delete keybox");}}).show();
        }).show();
    }
    private void inspectSavedXml(String xml){showDetailsXml(xml);}
    private void drawProfile()throws Exception{
        JSONObject root=config();JSONObject p=selected(root);
        group();text(p.getString("id"),22);
        button("Profile name",p.getString("id"),()->edit("Profile name",p.optString("id"),"Unique name",name->{selected(root).put("id",name);save(root);}));
        button("Keybox",p.getString("keybox").equals("current")?"Current imported keybox":p.getString("keybox"),()->{List<String> names=new ArrayList<>();names.add("current");for(Iterator<String> it=root.optJSONObject("keyboxes").keys();it.hasNext();)names.add(it.next());new AlertDialog.Builder(this).setTitle("Select keybox").setItems(names.toArray(new String[0]),(d,n)->{try{p.put("keybox",names.get(n));changed(root);}catch(Exception e){message("Keybox","Could not select keybox");}}).show();});
        button("Keybox details","Certificates, signatures and revocation",this::details);
        button("Target apps",p.getString("scope").equals("all")?"All remaining owner-user apps":p.getJSONArray("apps").length()+" selected packages",this::targets);
        button("Operation mode",p.optString("mode","patch"),()->new AlertDialog.Builder(this).setTitle("Operation mode").setItems(new String[]{"Patch · keep hardware keys","Generation · create software keys"},(d,n)->new AlertDialog.Builder(this).setTitle("Change operation mode?").setMessage("This changes how new keys are created and attested. Existing keys are not deleted. App compatibility may change.").setNegativeButton("Cancel",null).setPositiveButton("Apply",(confirm,w)->{try{p.put("mode",n==0?"patch":"generation");changed(root);}catch(Exception e){message("Profile","Could not change operation mode");}}).show()).show());
        group();text("Patch & OS levels",22);
        button("OS version",p.optString("osVersion","system_property"),()->edit("OS version",p.optString("osVersion","system_property"),"system_property, harvested or Android version",value->{p.put("osVersion",value);save(root);}));
        JSONObject levels=p.optJSONObject("patchLevel");if(levels==null){levels=new JSONObject();p.put("patchLevel",levels);}final JSONObject patch=levels;
        for(String field:new String[]{"system","vendor","boot"})button(field.substring(0,1).toUpperCase(Locale.ROOT)+field.substring(1)+" patch",patch.optString(field,"system_property"),()->edit("Patch level",patch.optString(field,"system_property"),"YYYY-MM-DD, system_property, harvested or no",value->{patch.put(field,value);save(root);}));
        note("Defaults use the ROM build properties. An override changes the attested value; it does not install a security update.");
        group();text("Device identity",22);JSONObject ids=p.optJSONObject("deviceIds");if(ids==null){ids=new JSONObject();p.put("deviceIds",ids);}final JSONObject identity=ids;
        for(String field:Profiles.IDS)button(field.substring(0,1).toUpperCase(Locale.ROOT)+field.substring(1),identity.optString(field,"").isEmpty()?"Use captured device value":identity.optString(field),()->edit(field,identity.optString(field,""),"Empty uses captured value",value->{identity.put(field,value);save(root);}));
        group();button("Delete profile","Keep at least one profile",()->new AlertDialog.Builder(this).setTitle("Delete profile?").setMessage("New attestation requests will use the remaining routing rules. Keep the keybox needed by existing simulator keys.").setNegativeButton("Cancel",null).setPositiveButton("Delete",(d,w)->{try{JSONObject next=config();next.getJSONArray("profiles").remove(profileIndex);save(next);profileIndex=0;page="profiles";draw();}catch(Exception e){message("Profile retained","Keep at least one profile.");}}).show());
    }
    private void additional(Uri uri){
        background(()->{String xml;try(InputStream in=getContentResolver().openInputStream(uri)){xml=new String(Keybox.read(in,Keybox.MAX_BYTES),StandardCharsets.UTF_8).replaceFirst("^\\uFEFF","");}boolean aosp=xml.equals(asset());Keybox parsed=Keybox.parse(xml,aosp);if(parsed.expired&&!aosp)throw new IllegalArgumentException("Expired certificate");return xml;},xml->{draw();new AlertDialog.Builder(this).setTitle("Save additional keybox?").setMessage("Certificate signatures and private-key matching passed. Check revocation in Keybox details before assigning it to apps. This does not replace your current keybox.").setNegativeButton("Cancel",null).setPositiveButton("Name keybox",(d,w)->edit("Keybox name","","Unique name",name->{JSONObject root=config();if(root.getJSONObject("keyboxes").has(name))throw new IllegalArgumentException("Name already exists");root.getJSONObject("keyboxes").put(name,xml);save(root);})).show();});
    }
    private void restore(){new AlertDialog.Builder(this).setTitle("Restore default routing?").setMessage("Use the current imported keybox in patch mode for all owner-user application UIDs. Your previous profiles are backed up for restoration. Additional keyboxes stay saved.").setNegativeButton("Cancel",null).setPositiveButton("Restore",(d,w)->{try{String raw=Settings.Secure.getString(getContentResolver(),Profiles.SETTING);JSONObject root=Profiles.defaults();
                try{JSONObject old=Profiles.load(raw);root.put("keyboxes",old.getJSONObject("keyboxes"));}catch(JSONException invalid){/* Keep the raw configuration in the private backup for recovery. */}
                if(raw!=null&&!Settings.Secure.putString(getContentResolver(),"unica_keybox_profiles_backup",raw))throw new IOException("Could not back up profiles");save(root);page="main";profileIndex=0;draw();}catch(Exception e){message("Restore defaults","Could not restore configuration");}}).show();}
    private void restoreBackup(){try{String raw=Settings.Secure.getString(getContentResolver(),"unica_keybox_profiles_backup");if(raw==null){message("Restore previous profiles","No previous profile configuration saved.");return;}JSONObject root=Profiles.load(raw);new AlertDialog.Builder(this).setTitle("Restore previous profiles?").setMessage("Reapply the configuration saved before restoring defaults.").setNegativeButton("Cancel",null).setPositiveButton("Restore",(d,w)->changed(root)).show();}catch(Exception e){message("Restore previous profiles","Saved backup is unavailable or invalid.");}}
    private void diagnostics(){if(!page.equals("logs"))scrollView=null;page="logs";background(()->{try{return Diagnostics.read();}catch(Exception e){return Collections.singletonList("W · Backend · Logs unavailable; access denied or log reader failed");}},rows->{logRows=rows;draw();});}
    private String diagnosticText(){return "UN1CA keybox diagnostics\n"+backendSummary()+"\nConfiguration error: "+backend("config_error")+"\n\n"+Diagnostics.filter(logRows,logSeverity,logArea,logQuery);}
    private void drawDiagnostics(){
        group();text("Diagnostics",22);text("Event summaries omit key material, certificate contents, app names, aliases and device identifiers.",16);
        button("Refresh logs","Read recent TEESimulator events",this::diagnostics);
        button("Minimum severity",logSeverity,()->new AlertDialog.Builder(this).setTitle("Minimum severity").setItems(new String[]{"V","D","I","W","E","F"},(d,n)->{logSeverity=new String[]{"V","D","I","W","E","F"}[n];draw();}).show());
        button("Subsystem",logArea,()->{String[] areas={"All","Backend","Configuration","Connection","Injector","Harvest","Attestation"};new AlertDialog.Builder(this).setTitle("Subsystem").setItems(areas,(d,n)->{logArea=areas[n];draw();}).show();});
        button("Search events",logQuery.isEmpty()?"All summaries":logQuery,()->edit("Search events",logQuery,"Summary text",value->{logQuery=value;draw();}));
        button("Export redacted diagnostics","Save the displayed status and filtered event summaries",()->{Intent intent=new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("text/plain").addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE,"unica-keybox-diagnostics.txt");exportSnapshot=diagnosticText();startActivityForResult(intent,44);});
        group();TextView logs=text(Diagnostics.filter(logRows,logSeverity,logArea,logQuery),15);logs.setTextIsSelectable(true);
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
