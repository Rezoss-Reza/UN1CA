/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.mesalabs.unica.gpu;

import java.io.*;
import java.nio.file.*;
import java.util.UUID;
import org.json.JSONObject;

final class GpuTransport {
    private static final File BASE=new File("/data/misc/unica_gpu");
    static final File ROLLBACK=new File("/cache/unica-gpu/rollback.zip");
    private static String read(File file)throws IOException {
        try(InputStream in=new FileInputStream(file);ByteArrayOutputStream out=new ByteArrayOutputStream()){
            byte[] b=new byte[4096];int n;while((n=in.read(b))!=-1){out.write(b,0,n);if(out.size()>16384)throw new IOException("Oversized GPU response");}return out.toString("UTF-8");
        }
    }
    static synchronized JSONObject request(String operation,String payload)throws Exception {
        if(android.os.Process.myUid()!=1000)throw new IOException("Available to the device owner's Settings only");
        if(!java.util.Arrays.asList("status","backup","renew","exported","apply","apply2","restore").contains(operation))throw new IOException("Invalid operation");
        if(!payload.matches("[0-9a-f ]{0,4096}"))throw new IOException("Invalid GPU request");
        File dir=new File(BASE,"requests"),request=new File(dir,"request"),response=new File(BASE,"responses/response.json");
        if(!dir.isDirectory())throw new IOException("GPU service is not installed. Install the updated ROM.");
        String id=UUID.randomUUID().toString();File temp=new File(dir,"request.tmp");
        try(FileOutputStream out=new FileOutputStream(temp)){out.write((id+" "+operation+" "+payload+"\n").getBytes("UTF-8"));out.getFD().sync();}
        android.system.Os.chmod(temp.getPath(),0640);
        Files.move(temp.toPath(),request.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
        java.lang.reflect.Method set=Class.forName("android.os.SystemProperties").getMethod("set",String.class,String.class);
        long deadline=android.os.SystemClock.elapsedRealtime()+180000,next=0;int attempt=0;
        while(android.os.SystemClock.elapsedRealtime()<deadline){
            if(response.exists()){JSONObject r=new JSONObject(read(response));if(id.equals(r.optString("id"))){JSONObject result=r.getJSONObject("result");if(result.has("error"))throw new IOException(result.getString("error"));return result;}}
            long now=android.os.SystemClock.elapsedRealtime();if(now>=next){set.invoke(null,"sys.unica.gpu.request",id+"-"+(attempt++));next=now+5000;}
            Thread.sleep(200);
        }
        throw new IOException("GPU operation timed out. Do not reboot or repeat Apply; refresh status first. The recovery rollback ZIP remains available.");
    }
}
