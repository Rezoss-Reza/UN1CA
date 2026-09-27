/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.mesalabs.unica.battery;
import org.json.*;
import java.io.*;
import java.nio.file.*;
import java.util.UUID;

/** Protected IPC to the fixed-control ROM kernel worker. No shell or root manager. */
public final class BatteryTransport {
    static final File BASE=new File("/data/misc/unica_battery");
    static final File REQUEST=new File(BASE,"requests/request"), RESPONSE=new File(BASE,"responses/response.json");
    static String read(File file,int limit)throws IOException {
        try(FileInputStream in=new FileInputStream(file);ByteArrayOutputStream out=new ByteArrayOutputStream()){
            byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1){out.write(b,0,n);if(out.size()>limit)throw new IOException("Battery message too large");}return out.toString("UTF-8");
        }
    }
    public static synchronized JSONObject request(String operation,String control,String value)throws Exception {
        if(android.os.Process.myUid()!=1000)throw new IOException("Battery Saving is available to the device owner's Settings only");
        if(!REQUEST.getParentFile().isDirectory())throw new IOException("Battery Saving service is not installed. Install the updated ROM.");
        if(!java.util.Arrays.asList("list","get","set").contains(operation))throw new IOException("Invalid kernel operation");
        if(!operation.equals("list")&&!control.matches("wifi_pm|wifi_wakeup|aspm|swappiness|gpu|cpu_policy[0-9]{1,3}"))throw new IOException("Invalid kernel control");
        if(operation.equals("set")&&!value.matches("[a-z0-9_]{1,20}"))throw new IOException("Invalid kernel value");
        String id=UUID.randomUUID().toString(),message=id+"\n"+operation+"\n";
        if(!operation.equals("list"))message+=control+"\n";
        if(operation.equals("set"))message+=value+"\n";
        File temp=new File(REQUEST.getPath()+".tmp");
        try(FileOutputStream out=new FileOutputStream(temp)){out.write(message.getBytes("UTF-8"));out.getFD().sync();}
        android.system.Os.chmod(temp.getPath(),0640);
        Files.move(temp.toPath(),REQUEST.toPath(),StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);
        java.lang.reflect.Method set=Class.forName("android.os.SystemProperties").getMethod("set",String.class,String.class);
        long deadline=android.os.SystemClock.elapsedRealtime()+15000,nextTrigger=0;int attempt=0;
        while(android.os.SystemClock.elapsedRealtime()<deadline){
            if(RESPONSE.exists()){
                JSONObject reply=new JSONObject(read(RESPONSE,65536));
                if(id.equals(reply.optString("id"))){JSONObject result=reply.getJSONObject("result");if(result.has("error"))throw new IOException(result.getString("error"));return result;}
            }
            long now=android.os.SystemClock.elapsedRealtime();
            if(now>=nextTrigger){set.invoke(null,"sys.unica.battery.request",id+"-"+(attempt++));nextTrigger=now+2000;}
            Thread.sleep(100);
        }
        throw new IOException("Battery kernel service did not respond. Refresh status before retrying.");
    }
}
