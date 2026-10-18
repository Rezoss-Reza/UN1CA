/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.mesalabs.unica.gpu;

import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.util.*;
import org.json.*;

/** Typed values only. The native worker independently validates every cell. */
final class GpuProfile {
    static final int MAX_BYTES=32768;
    static final int[] STOCK_LEVELS={52,56,60,64,80,128,144,192,224};
    static final class Row {long frequency,acd,bus,minimum,maximum;int voltage;boolean hasAcd;}
    static final class Bin {int initial,minimum;final ArrayList<Row> rows=new ArrayList<>();}
    final Bin[] tables={new Bin(),new Bin()};
    String name,firmware;
    private GpuProfile(){}
    static boolean outsideStock(int level){for(int i:STOCK_LEVELS)if(level==i)return false;return true;}
    static boolean stockFrequency(int table,long value){try{for(Row row:stock().tables[table].rows)if(row.frequency==value)return true;}catch(Exception impossible){throw new IllegalStateException(impossible);}return false;}
    static GpuProfile stock()throws Exception {return fromTables("My GPU profile","Pinned Samsung S918BXXUAZZI8 table defaults",new JSONArray(StockTables.JSON));}
    static GpuProfile fromTables(String name,String firmware,JSONArray tables)throws Exception {
        JSONObject o=new JSONObject().put("format","unica-konabess-profile").put("version",2).put("device","dm3q").put("gpu","Adreno740v2").put("table","kalama-v2-bins").put("name",name).put("source_firmware",firmware).put("tables",tables);return fromJson(o);
    }
    private static GpuProfile fromJson(JSONObject o)throws Exception {
        long version=integer(o,"version");boolean legacy=version==1;
        fields(o,"format","version","device","gpu","table","name","source_firmware",legacy?"power_levels":"tables");
        if(!"unica-konabess-profile".equals(o.get("format"))||(version!=1&&version!=2)||!"dm3q".equals(o.get("device"))||!"Adreno740v2".equals(o.get("gpu"))||!(legacy?"kalama-v2-table0":"kalama-v2-bins").equals(o.get("table")))throw new IllegalArgumentException("Profile format or target GPU does not match this device");
        if(legacy){
            GpuProfile p=stock();p.name=plain(o,"name",80);p.firmware=plain(o,"source_firmware",256);JSONArray a=o.getJSONArray("power_levels");if(a.length()!=10)throw new IllegalArgumentException("Legacy profile needs ten levels");
            for(int i=0;i<10;i++){JSONObject row=a.getJSONObject(i);fields(row,"index","frequency_hz","rpmh_level");if(integer(row,"index")!=i)throw new IllegalArgumentException("Bad legacy index");p.tables[0].rows.get(i).frequency=integer(row,"frequency_hz");p.tables[0].rows.get(i).voltage=bounded(row,"rpmh_level",1,480);}p.validate();return p;
        }
        GpuProfile p=new GpuProfile();p.name=plain(o,"name",80);p.firmware=plain(o,"source_firmware",256);JSONArray bins=o.getJSONArray("tables");if(bins.length()!=2)throw new IllegalArgumentException("Expected both Samsung GPU tables");
        for(int id=0;id<2;id++){
            JSONObject b=bins.getJSONObject(id);fields(b,"id","initial_level","minimum_level","power_levels");if(integer(b,"id")!=id)throw new IllegalArgumentException("GPU table IDs must be 0 then 1");Bin bin=p.tables[id];bin.initial=bounded(b,"initial_level",0,14);bin.minimum=bounded(b,"minimum_level",0,14);JSONArray entries=b.getJSONArray("power_levels");if(entries.length()<2||entries.length()>15)throw new IllegalArgumentException("Expected 2–15 power levels per table");
            for(int i=0;i<entries.length();i++){
                JSONObject row=entries.getJSONObject(i);fields(row,"index","frequency_hz","rpmh_level","bus_frequency","bus_minimum","bus_maximum","acd_level");if(integer(row,"index")!=i)throw new IllegalArgumentException("GPU row indices must be contiguous");Row r=new Row();r.frequency=integer(row,"frequency_hz");r.voltage=bounded(row,"rpmh_level",1,480);r.bus=integer(row,"bus_frequency");r.minimum=integer(row,"bus_minimum");r.maximum=integer(row,"bus_maximum");r.hasAcd=!row.isNull("acd_level");if(r.hasAcd)r.acd=integer(row,"acd_level");bin.rows.add(r);
            }
        }p.validate();return p;
    }
    void validate(){
        int size=StockTables.OVERHEAD;
        for(Bin bin:tables){if(bin.rows.size()<2||bin.rows.size()>15||bin.initial<0||bin.minimum<0||bin.initial>=bin.rows.size()||bin.minimum>=bin.rows.size())throw new IllegalArgumentException("Table count or initial/minimum index is invalid");long previous=Long.MAX_VALUE;
            for(int i=0;i<bin.rows.size();i++){Row r=bin.rows.get(i);if(i+1==bin.rows.size()&&r.maximum>9)throw new IllegalArgumentException("Final GPU row bypasses the driver bus clamp: bus maximum must be 0–9 (got "+r.maximum+")");if(r.frequency<=0||r.frequency>0xffffffffL||r.frequency>=previous||r.voltage<1||r.voltage>480||r.minimum<0||r.minimum>r.bus||r.bus>r.maximum||r.maximum>0xffffffffL||r.acd<0||r.acd>0xffffffffL)throw new IllegalArgumentException("Invalid GPU frequency, RPMh request, bus ordering or ACD cell at level "+i);previous=r.frequency;int length=("qcom,gpu-pwrlevel@"+i).length()+1;size+=8+(length+3)/4*4+16*(r.hasAcd?7:6);}
        }
        if(size>StockTables.CAPACITY)throw new IllegalArgumentException("Tables exceed the original FDT space. Remove a level before adding another.");
    }
    JSONArray tablesJson()throws Exception {
        JSONArray bins=new JSONArray();for(int id=0;id<2;id++){Bin bin=tables[id];JSONArray rows=new JSONArray();for(int i=0;i<bin.rows.size();i++){Row r=bin.rows.get(i);rows.put(new JSONObject().put("index",i).put("frequency_hz",r.frequency).put("rpmh_level",r.voltage).put("bus_frequency",r.bus).put("bus_minimum",r.minimum).put("bus_maximum",r.maximum).put("acd_level",r.hasAcd?r.acd:JSONObject.NULL));}bins.put(new JSONObject().put("id",id).put("initial_level",bin.initial).put("minimum_level",bin.minimum).put("power_levels",rows));}return bins;
    }
    GpuProfile copy()throws Exception {return parse(encode());}
    String payload(String hash){validate();StringBuilder s=new StringBuilder(hash);for(Bin b:tables){s.append(' ').append(b.rows.size()).append(' ').append(b.initial).append(' ').append(b.minimum);for(Row r:b.rows)s.append(' ').append(r.frequency).append(' ').append(r.voltage).append(' ').append(r.bus).append(' ').append(r.minimum).append(' ').append(r.maximum).append(' ').append(r.hasAcd?1:0).append(' ').append(r.hasAcd?r.acd:0);}return s.toString();}
    static GpuProfile parse(byte[] bytes)throws Exception {
        String text=decode(bytes).trim();if(text.startsWith("konabess://"))return KonaProfileImport.parse(text);
        return fromJson(object(text));
    }
    static JSONObject object(String text)throws Exception {
        int depth=0;boolean quoted=false,escaped=false;
        for(int i=0;i<text.length();i++){char c=text.charAt(i);if(quoted){if(escaped)escaped=false;else if(c=='\\')escaped=true;else if(c=='"')quoted=false;}else if(c=='"')quoted=true;else if(c=='{'||c=='['){if(++depth>12)throw new IllegalArgumentException("Profile JSON is too deeply nested");}else if(c=='}'||c==']'){if(--depth<0)throw new IllegalArgumentException("Unbalanced profile JSON");}}
        if(depth!=0||quoted)throw new IllegalArgumentException("Truncated profile JSON");JSONTokener t=new JSONTokener(text);Object o=t.nextValue();if(!(o instanceof JSONObject)||t.nextClean()!=0)throw new IllegalArgumentException("Expected one JSON profile");return (JSONObject)o;
    }
    static String decode(byte[] bytes)throws Exception {if(bytes.length==0||bytes.length>MAX_BYTES)throw new IllegalArgumentException("Profile must be 1–32768 bytes");return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();}
    byte[] encode()throws Exception {validate();JSONObject o=new JSONObject().put("format","unica-konabess-profile").put("version",2).put("device","dm3q").put("gpu","Adreno740v2").put("table","kalama-v2-bins").put("name",name).put("source_firmware",firmware).put("tables",tablesJson());return (o.toString(2)+"\n").getBytes(StandardCharsets.UTF_8);}
    static void fields(JSONObject o,String... names)throws Exception {Set<String> allowed=new HashSet<>(Arrays.asList(names));Iterator<String> keys=o.keys();while(keys.hasNext())if(!allowed.contains(keys.next()))throw new IllegalArgumentException("Unknown profile field");for(String name:names)if(!o.has(name))throw new IllegalArgumentException("Missing profile field: "+name);}
    static long integer(JSONObject o,String key)throws Exception {Object v=o.get(key);if(!(v instanceof Integer)&&!(v instanceof Long))throw new IllegalArgumentException("Expected whole number: "+key);return ((Number)v).longValue();}
    private static int bounded(JSONObject o,String key,int min,int max)throws Exception {long v=integer(o,key);if(v<min||v>max)throw new IllegalArgumentException(key+" must be "+min+"–"+max);return (int)v;}
    private static String plain(JSONObject o,String key,int max)throws Exception {Object v=o.get(key);if(!(v instanceof String)||((String)v).length()>max)throw new IllegalArgumentException("Invalid profile text: "+key);String s=(String)v;for(int i=0;i<s.length();i++)if(Character.isISOControl(s.charAt(i)))throw new IllegalArgumentException("Control characters in profile text");return s;}
}
