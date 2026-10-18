/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.mesalabs.unica.gpu;
import java.nio.charset.StandardCharsets;
import org.json.*;

public final class GpuProfileTest {
    private static int checks;
    private static final long[] F={719000000,680000000,615000000,550000000,475000000,401000000,348000000,295000000,220000000,124800000};
    private static final int[] L={224,192,144,128,80,64,60,56,52,52};
    private static void check(boolean b){if(!b)throw new AssertionError();checks++;}
    private static JSONObject valid()throws Exception {return new JSONObject(new String(GpuProfile.create("Shared GPU profile","old ROM fingerprint",F,L).encode(),StandardCharsets.UTF_8));}
    private static GpuProfile parse(JSONObject o)throws Exception {return GpuProfile.parse(o.toString().getBytes(StandardCharsets.UTF_8));}
    private static void reject(byte[] bytes)throws Exception {try{GpuProfile.parse(bytes);}catch(Exception expected){checks++;return;}throw new AssertionError("Accepted invalid profile");}
    private static void reject(JSONObject o)throws Exception {reject(o.toString().getBytes(StandardCharsets.UTF_8));}
    public static void main(String[] args)throws Exception {
        GpuProfile p=parse(valid());check(java.util.Arrays.equals(F,p.frequencies));check(java.util.Arrays.equals(L,p.levels));check(p.name.equals("Shared GPU profile"));
        check(java.util.Arrays.equals(GpuProfile.parse(p.encode()).frequencies,F));
        JSONObject o=valid().put("source_firmware","different compatible ROM");check(parse(o).frequencies[0]==F[0]);
        for(String key:new String[]{"format","device","gpu","table"})reject(valid().put(key,"wrong"));
        for(Object v:new Object[]{2,"1",true,JSONObject.NULL})reject(valid().put("version",v));
        reject(valid().toString().replace("\"version\":1", "\"version\":1.0").getBytes(StandardCharsets.UTF_8));
        reject(valid().put("command","flash /dev/block/sda28"));reject(valid().put("image_path","/tmp/image.img"));
        o=valid();o.remove("device");reject(o);reject(valid().put("name","bad\nname"));reject(valid().put("source_firmware",new String(new char[257]).replace('\0','x')));
        reject(valid().put("name",42));reject(valid().put("power_levels",new JSONArray()));reject(valid().put("power_levels","table"));
        for(Object value:new Object[]{"719000000",719000000.5,true,JSONObject.NULL,850100000L,124700000L,719000001L}){o=valid();o.getJSONArray("power_levels").getJSONObject(0).put("frequency_hz",value);reject(o);}
        for(Object value:new Object[]{53,"224",224.5,true,256,-1}){o=valid();o.getJSONArray("power_levels").getJSONObject(0).put("rpmh_level",value);reject(o);}
        o=valid();o.getJSONArray("power_levels").getJSONObject(0).put("index",1);reject(o);
        o=valid();o.getJSONArray("power_levels").getJSONObject(1).put("frequency_hz",F[0]);reject(o);
        o=valid();o.getJSONArray("power_levels").getJSONObject(1).put("frequency_hz",800000000);reject(o);
        o=valid();o.getJSONArray("power_levels").getJSONObject(2).put("rpmh_level",224);reject(o);
        o=valid();o.getJSONArray("power_levels").getJSONObject(0).put("path","/dev/block/sda28");reject(o);
        reject(new byte[0]);reject(new byte[GpuProfile.MAX_BYTES+1]);reject(new byte[]{(byte)0xc3,(byte)0x28});reject("[]".getBytes(StandardCharsets.UTF_8));reject("{}".getBytes(StandardCharsets.UTF_8));reject((valid().toString()+" {}").getBytes(StandardCharsets.UTF_8));
        long[] small=F.clone();small[0]=700000000;check(GpuProfile.create("Trial","ROM",small,L).frequencies[0]==700000000);
        check(F[0]==719000000);System.out.println("PASS "+checks+" profile round-trip, cross-ROM metadata, schema, bounds, type, encoding and untrusted-field assertions");
    }
}
