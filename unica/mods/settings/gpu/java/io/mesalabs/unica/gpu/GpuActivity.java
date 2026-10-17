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
    private static final int EXPORT=51;
    private static final int[] LEVELS={52,56,60,64,80,128,144,192,224};
    private static final String[] LABELS={"52 · LOW_SVS_D2","56 · LOW_SVS_D1","60 · LOW_SVS_D0","64 · LOW_SVS","80 · LOW_SVS_L1","128 · SVS","144 · SVS_L0","192 · SVS_L1","224 · SVS_L2"};
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final EditText[] frequency=new EditText[10];
    private final Spinner[] level=new Spinner[10];
    private JSONObject state;
    private boolean busy;
    private String message="Checking firmware compatibility…";
    private LinearLayout content;
    private int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
    @Override public void onCreate(Bundle b){super.onCreate(b);setTitle("Advanced GPU Tuning (Konabess)");if(getActionBar()!=null)getActionBar().setDisplayHomeAsUpEnabled(true);draw();run("status","");}
    @Override public boolean onOptionsItemSelected(MenuItem item){if(item.getItemId()==android.R.id.home){onBackPressed();return true;}return super.onOptionsItemSelected(item);}
    @Override public void onBackPressed(){if(busy){Toast.makeText(this,"Wait for the GPU operation to finish",Toast.LENGTH_SHORT).show();return;}super.onBackPressed();}
    @Override protected void onDestroy(){worker.shutdown();super.onDestroy();}
    private void text(String s,int size){TextView v=new TextView(this);v.setText(s);v.setTextSize(size);v.setPadding(0,dp(8),0,dp(8));content.addView(v);}
    private void button(String label,boolean enabled,View.OnClickListener listener){Button b=new Button(this);b.setText(label);b.setAllCaps(false);b.setEnabled(enabled&&!busy);b.setOnClickListener(listener);content.addView(b,new LinearLayout.LayoutParams(-1,-2));}
    private boolean ready(){return state!=null&&!busy;}
    private String hash(){return state==null?"":state.optString("currentHash");}
    private void draw(){
        ScrollView scroll=new ScrollView(this);content=new LinearLayout(this);content.setOrientation(LinearLayout.VERTICAL);content.setPadding(dp(24),dp(16),dp(24),dp(32));scroll.addView(content);setContentView(scroll);
        scroll.setOnApplyWindowInsetsListener((v,i)->{android.graphics.Insets bars=i.getInsets(WindowInsets.Type.systemBars());v.setPadding(bars.left,bars.top,bars.right,bars.bottom);return i;});
        text("Advanced GPU Tuning (Konabess)",22);
        text("Trial for S23 Ultra / dm3q. Changes modify vendor_boot and take effect after reboot. Unsuitable frequencies or voltage levels can cause display corruption, freezes or a bootloop. Battery savings are not guaranteed.",15);
        text(message,14);
        button("Refresh image and compatibility",true,v->run("status",""));
        text("1. Prepare recovery rollback",19);
        text("Keep OrangeFox recovery available. The original image and rollback ZIP are retained in /cache/unica-gpu. Save another copy to a PC or USB drive before Apply. A Settings button cannot repair a phone that cannot boot.",14);
        button("Create or verify original backup",ready()&&(!state.optBoolean("backup")||state.optBoolean("original")),v->run("backup",hash()));
        button("Save rollback ZIP",ready()&&state.optBoolean("backup"),v->{Intent i=new Intent(Intent.ACTION_CREATE_DOCUMENT);i.addCategory(Intent.CATEGORY_OPENABLE);i.setType("application/zip");i.putExtra(Intent.EXTRA_TITLE,"UN1CA-dm3q-GPU-rollback.zip");try{startActivityForResult(i,EXPORT);}catch(ActivityNotFoundException e){alert("File picker unavailable",e.getMessage());}});
        button("Recovery instructions",true,v->alert("If Android cannot start","Boot into OrangeFox using the device's recovery procedure. Mount Cache. Flash /cache/unica-gpu/rollback.zip, or flash the saved rollback ZIP from USB/PC storage. It verifies and restores the exact pre-trial vendor_boot image. Reboot manually after success. Keep a matching full ROM available if recovery restoration fails. Never use this ZIP after changing firmware."));
        text("2. Edit GPU power levels",19);
        text("Frequency is MHz (0.1 MHz steps, 124.8–850). This range is an input limit, not a promise of hardware stability. Levels are Qualcomm RPMh requests, not millivolts; the platform may round them up. Keep frequencies descending and levels unchanged or descending down the table. Start with one small change.",14);
        if(state!=null){JSONArray f=state.optJSONArray("frequencies"),l=state.optJSONArray("levels");
            for(int row=0;row<10;row++){
                text("Power level "+row,16);LinearLayout line=new LinearLayout(this);line.setOrientation(LinearLayout.HORIZONTAL);
                EditText e=new EditText(this);e.setSingleLine(true);e.setInputType(InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_FLAG_DECIMAL);e.setContentDescription("Power level "+row+" frequency in MHz");e.setText(BigDecimal.valueOf(f.optLong(row)).movePointLeft(6).stripTrailingZeros().toPlainString());e.setEnabled(!busy&&!state.optBoolean("interrupted"));line.addView(e,new LinearLayout.LayoutParams(0,-2,1));frequency[row]=e;
                Spinner s=new Spinner(this);s.setAdapter(new ArrayAdapter<String>(this,android.R.layout.simple_spinner_dropdown_item,LABELS));int selected=0;for(int k=0;k<LEVELS.length;k++)if(LEVELS[k]==l.optInt(row))selected=k;s.setSelection(selected);s.setContentDescription("Power level "+row+" RPMh level");s.setEnabled(e.isEnabled());line.addView(s,new LinearLayout.LayoutParams(0,-2,2));level[row]=s;content.addView(line);
            }
        }
        button("Apply GPU table",ready()&&state.optBoolean("exported")&&state.optBoolean("canRestore")&&!state.optBoolean("interrupted"),v->apply());
        text("3. Restore or reboot",19);
        text("Apply verifies the partition after writing. Nothing reboots automatically. To undo the trial, restore the saved original and then reboot. After an interrupted write, restore before rebooting.",14);
        button("Restore original vendor_boot",ready()&&state.optBoolean("canRestore"),v->new AlertDialog.Builder(this).setTitle("Restore original GPU image?").setMessage("This restores the full vendor_boot image captured before this trial, including its original GPU table. Reboot after restoration succeeds.").setPositiveButton("Restore",(d,w)->run("restore",hash())).setNegativeButton("Cancel",null).show());
        button("Reboot now",ready()&&!state.optBoolean("interrupted"),v->new AlertDialog.Builder(this).setTitle("Reboot device?").setMessage("Keep the rollback ZIP and recovery available. Only reboot after Apply or Restore reports success.").setPositiveButton("Reboot",(d,w)->((android.os.PowerManager)getSystemService(POWER_SERVICE)).reboot(null)).setNegativeButton("Cancel",null).show());
        text("Inspired by libxzr/KonaBess. This ROM trial edits the existing ten entries only; it preserves bin-selection, bus, ACD and other device-tree properties.",12);
    }
    private void alert(String title,String body){new AlertDialog.Builder(this).setTitle(title).setMessage(body).setPositiveButton("OK",null).show();}
    private void apply(){
        try{StringBuilder payload=new StringBuilder(hash());StringBuilder changes=new StringBuilder();JSONArray oldF=state.getJSONArray("frequencies"),oldL=state.getJSONArray("levels");long previous=Long.MAX_VALUE;int prevLevel=Integer.MAX_VALUE;boolean changed=false;
            for(int i=0;i<10;i++){long f=new BigDecimal(frequency[i].getText().toString()).movePointRight(6).longValueExact();int l=LEVELS[level[i].getSelectedItemPosition()];if(f<124800000||f>850000000||f%100000!=0||f>=previous||l>prevLevel)throw new IllegalArgumentException("Check frequency range, 0.1 MHz steps and descending frequency/voltage order.");previous=f;prevLevel=l;payload.append(' ').append(f).append(' ').append(l);if(f!=oldF.getLong(i)||l!=oldL.getInt(i)){changed=true;changes.append("Level ").append(i).append(": ").append(oldF.getLong(i)/1000000.0).append(" → ").append(f/1000000.0).append(" MHz; RPMh ").append(oldL.getInt(i)).append(" → ").append(l).append('\n');}}
            if(!changed)throw new IllegalArgumentException("No GPU changes selected.");
            new AlertDialog.Builder(this).setTitle("Write GPU trial to vendor_boot?").setMessage(changes+"\nThis can prevent booting. Confirm that your saved rollback ZIP and OrangeFox recovery are available. Keep the device powered until verification finishes.").setPositiveButton("Apply trial",(d,w)->run("apply",payload.toString())).setNegativeButton("Cancel",null).show();
        }catch(Exception e){alert("Cannot apply",e.getMessage());}
    }
    private void run(String operation,String payload){if(busy)return;busy=true;message="Working: "+operation+". Keep the device powered…";draw();
        worker.execute(()->{JSONObject result=null;String error=null;try{result=GpuTransport.request(operation,payload);}catch(Exception e){error=e.getMessage();}final JSONObject reply=result;final String fail=error;
            runOnUiThread(()->{if(isDestroyed())return;busy=false;if(reply!=null){state=reply;message=(operation.equals("apply")?"GPU table written and verified. Reboot when ready.":operation.equals("restore")?"Original vendor_boot restored and verified. Reboot when ready.":"Compatible firmware. Backup: "+(reply.optBoolean("backup")?"saved":"not created")+". Rollback export: "+(reply.optBoolean("exported")?"verified":"required before Apply")+".");if(reply.optBoolean("interrupted"))message="Interrupted write detected. Restore the original before rebooting.";}else{state=null;message="Operation failed: "+fail+". Refresh status before any further action.";alert("GPU operation failed",message);}draw();});
        });
    }
    private static String hex(byte[] b){StringBuilder out=new StringBuilder();for(byte v:b)out.append(String.format(java.util.Locale.US,"%02x",v&255));return out.toString();}
    @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);if(request!=EXPORT||result!=RESULT_OK||data==null||data.getData()==null)return;
        if(!ready())return;Uri uri=data.getData();String expected=state.optString("rollbackHash"),current=hash();busy=true;message="Saving and verifying recovery rollback ZIP…";draw();
        worker.execute(()->{JSONObject reply=null;String error=null;try{
            MessageDigest digest=MessageDigest.getInstance("SHA-256");long total=0;try(InputStream in=new FileInputStream(GpuTransport.ROLLBACK);OutputStream out=getContentResolver().openOutputStream(uri,"wt")){if(out==null)throw new IOException("Cannot open destination");byte[] b=new byte[65536];int n;while((n=in.read(b))!=-1){total+=n;if(total>120*1024*1024)throw new IOException("Oversized rollback ZIP");out.write(b,0,n);digest.update(b,0,n);}}
            if(!expected.equals(hex(digest.digest())))throw new IOException("Source rollback checksum mismatch");digest.reset();long read=0;
            try(InputStream in=getContentResolver().openInputStream(uri)){if(in==null)throw new IOException("Cannot verify saved ZIP");byte[] b=new byte[65536];int n;while((n=in.read(b))!=-1){read+=n;if(read>120*1024*1024)throw new IOException("Oversized saved ZIP");digest.update(b,0,n);}}
            if(read!=total||!expected.equals(hex(digest.digest())))throw new IOException("Saved rollback ZIP verification failed");reply=GpuTransport.request("exported",current+" "+expected);
        }catch(Exception e){error=e.getMessage();}final JSONObject r=reply;final String fail=error;runOnUiThread(()->{if(isDestroyed())return;busy=false;if(r!=null){state=r;message="Rollback ZIP saved and verified. Keep it accessible from recovery.";}else{message="Rollback export failed: "+fail;alert("Rollback export failed",message);}draw();});});
    }
}
