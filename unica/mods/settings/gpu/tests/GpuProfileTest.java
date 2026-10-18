/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.mesalabs.unica.gpu;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.io.*;
import java.util.*;
import java.util.zip.GZIPOutputStream;
import org.json.*;

public final class GpuProfileTest {
    private static int checks;
    private static void check(boolean value){if(!value)throw new AssertionError();checks++;}
    private static JSONObject valid()throws Exception {return new JSONObject(new String(GpuProfile.stock().encode(),StandardCharsets.UTF_8));}
    private static JSONObject row(JSONObject o,int table,int row)throws Exception {return o.getJSONArray("tables").getJSONObject(table).getJSONArray("power_levels").getJSONObject(row);}
    private static GpuProfile parse(JSONObject o)throws Exception {return GpuProfile.parse(o.toString().getBytes(StandardCharsets.UTF_8));}
    private static void reject(byte[] bytes)throws Exception {try{GpuProfile.parse(bytes);}catch(Exception expected){checks++;return;}throw new AssertionError("Accepted invalid profile");}
    private static void reject(JSONObject o)throws Exception {reject(o.toString().getBytes(StandardCharsets.UTF_8));}
    private static String packed(JSONObject o)throws Exception {ByteArrayOutputStream out=new ByteArrayOutputStream();try(GZIPOutputStream zip=new GZIPOutputStream(out)){zip.write(o.toString().getBytes(StandardCharsets.UTF_8));}return "konabess://"+Base64.getEncoder().encodeToString(out.toByteArray());}
    private static JSONObject kona(GpuProfile p)throws Exception {
        StringBuilder dts=new StringBuilder();for(int id=0;id<2;id++){GpuProfile.Bin b=p.tables[id];dts.append("qcom,gpu-pwrlevels-").append(id).append(" {\n#size-cells = <0>; #address-cells = <1>;\nqcom,sku-codes = <").append(id==0?"3 6 0x300f1 0x300f3 0x500f1 0x500f3 0x700f1 0x700f2 0x700fe 0x700ff":"0").append(">;\nqcom,initial-pwrlevel = <").append(b.initial).append(">; qcom,initial-min-pwrlevel = <").append(b.minimum).append(">;\n");for(int i=0;i<b.rows.size();i++){GpuProfile.Row r=b.rows.get(i);dts.append("qcom,gpu-pwrlevel@").append(i).append(" { reg = <").append(i).append(">; qcom,gpu-freq = <").append(r.frequency).append(">; qcom,level = <").append(r.voltage).append(">; qcom,bus-freq = <").append(r.bus).append(">; qcom,bus-min = <").append(r.minimum).append(">; qcom,bus-max = <").append(r.maximum).append(">; ");if(r.hasAcd)dts.append("qcom,acd-level = <").append(r.acd).append(">;");dts.append(" };\n");}dts.append("};\n");}return new JSONObject().put("chip","kalama").put("desc","Shared profile").put("freq",dts.toString());
    }
    public static void main(String[] args)throws Exception {
        GpuProfile p=parse(valid());check(p.tables[0].rows.size()==10);check(p.tables[1].rows.size()==9);check(p.tables[0].rows.get(0).frequency==719000000);check(p.tables[0].rows.get(0).voltage==224);
        p.name="Quoted \" { [] } \" profile";check(p.copy().name.equals(p.name));check(p.tablesJson().toString().equals(p.copy().tablesJson().toString()));check(parse(valid().put("source_firmware","another ROM")).tables[0].rows.size()==10);
        for(int value:new int[]{1,16,48,72,96,193,480}){JSONObject o=valid();row(o,0,0).put("rpmh_level",value);check(parse(o).tables[0].rows.get(0).voltage==value);check(GpuProfile.outsideStock(value));}
        check(!GpuProfile.outsideStock(224));JSONObject o=valid();row(o,0,0).put("frequency_hz",730999999);check(parse(o).tables[0].rows.get(0).frequency==730999999);
        o=valid();row(o,1,8).put("rpmh_level",56);check(parse(o).tables[1].rows.get(8).voltage==56); // Driver mapping accepts unordered requests.
        o=valid();row(o,0,0).put("acd_level",0xffffffffL);check(parse(o).tables[0].rows.get(0).acd==0xffffffffL);
        for(String key:new String[]{"format","device","gpu","table"})reject(valid().put(key,"wrong"));
        for(Object value:new Object[]{0,3,"2",true,JSONObject.NULL})reject(valid().put("version",value));
        reject(valid().toString().replace("\"version\":2","\"version\":2.0").getBytes(StandardCharsets.UTF_8));
        reject(valid().put("path","/dev/block/sda28"));reject(valid().put("command","flash"));reject(valid().put("name","bad\nname"));reject(valid().put("name",42));
        for(Object value:new Object[]{"719000000",719000000.5,true,JSONObject.NULL,0,-1,4294967296L}){o=valid();row(o,0,0).put("frequency_hz",value);reject(o);}
        for(Object value:new Object[]{0,-1,481,"96",72.5,true,JSONObject.NULL}){o=valid();row(o,0,0).put("rpmh_level",value);reject(o);}
        o=valid();row(o,0,1).put("frequency_hz",719000000);reject(o);o=valid();row(o,0,0).put("index",1);reject(o);
        for(String key:new String[]{"bus_frequency","bus_minimum","bus_maximum"}){o=valid();row(o,0,9).put(key,10);reject(o);}
        o=valid();row(o,0,0).put("bus_minimum",9).put("bus_frequency",8);reject(o);o=valid();row(o,0,0).put("acd_level",4294967296L);reject(o);
        o=valid();row(o,0,0).put("executable","bad");reject(o);o=valid();o.getJSONArray("tables").getJSONObject(0).put("id",1);reject(o);o=valid();o.getJSONArray("tables").getJSONObject(0).put("initial_level",10);reject(o);
        GpuProfile reduced=p.copy();reduced.tables[0].rows.remove(9);reduced.validate();check(reduced.copy().tables[0].rows.size()==9);
        GpuProfile overflow=p.copy();GpuProfile.Row r=new GpuProfile.Row();r.frequency=100000000;r.voltage=52;r.bus=1;r.minimum=1;r.maximum=1;overflow.tables[1].rows.add(r);try{overflow.validate();throw new AssertionError();}catch(IllegalArgumentException expected){checks++;}
        JSONObject legacy=new JSONObject().put("format","unica-konabess-profile").put("version",1).put("device","dm3q").put("gpu","Adreno740v2").put("table","kalama-v2-table0").put("name","Legacy").put("source_firmware","old");JSONArray entries=new JSONArray();for(int i=0;i<10;i++)entries.put(new JSONObject().put("index",i).put("frequency_hz",p.tables[0].rows.get(i).frequency).put("rpmh_level",p.tables[0].rows.get(i).voltage));legacy.put("power_levels",entries);check(parse(legacy).tables[1].rows.size()==9);
        JSONObject original=kona(p);GpuProfile imported=GpuProfile.parse(packed(original).getBytes(StandardCharsets.UTF_8));check(imported.tablesJson().toString().equals(p.tablesJson().toString()));
        GpuProfile exact=p.copy();exact.tables[0].rows.get(0).frequency=730999999;exact.tables[0].rows.get(0).voltage=1;exact.tables[0].rows.remove(9);exact.tables[1].rows.get(8).voltage=56;check(GpuProfile.parse(packed(kona(exact)).getBytes(StandardCharsets.UTF_8)).tablesJson().toString().equals(exact.tablesJson().toString()));
        reject(packed(kona(p).put("chip","pineapple")).getBytes(StandardCharsets.UTF_8));reject(packed(kona(p).put("volt","other voltage table")).getBytes(StandardCharsets.UTF_8));
        for(String bad:new String[]{"/include/ \"/tmp/bad\"",original.getString("freq").replace("qcom,sku-codes = <0>","qcom,sku-codes = <1>"),original.getString("freq")+";",original.getString("freq").replace("qcom,level","qcom,evil"),original.getString("freq").replace("<224>","<4294967296>")})reject(packed(new JSONObject().put("chip","kalama").put("desc","Bad").put("freq",bad)).getBytes(StandardCharsets.UTF_8));
        reject("konabess://bad!".getBytes(StandardCharsets.UTF_8));reject(packed(new JSONObject().put("padding",new String(new char[40000]).replace('\0','x'))).getBytes(StandardCharsets.UTF_8));
        reject("[[[[[[[[[[[[[0]]]]]]]]]]]]]".getBytes(StandardCharsets.UTF_8));reject("{\"nested\":\"escaped \\\" { [ \\\"\"}".getBytes(StandardCharsets.UTF_8));reject(new byte[0]);reject(new byte[GpuProfile.MAX_BYTES+1]);reject(new byte[]{(byte)0xc3,0x28});reject("[]".getBytes(StandardCharsets.UTF_8));reject((valid().toString()+" {}").getBytes(StandardCharsets.UTF_8));
        o=valid();row(o,0,0).put("bus_frequency",15).put("bus_minimum",14).put("bus_maximum",16);check(parse(o).tables[0].rows.get(0).maximum==16);
        for(String path:args){GpuProfile x=GpuProfile.parse(Files.readAllBytes(Paths.get(path)));check(x.tables[0].rows.get(0).frequency==730000000);check(x.tables[0].rows.get(1).voltage==96);check(x.tables[0].rows.get(3).voltage==72);System.out.println("PASS exact upstream export: "+path);}
        System.out.println("PASS "+checks+" profile, migration, native-export format, corner, width, structural and untrusted-input assertions");
    }
}
