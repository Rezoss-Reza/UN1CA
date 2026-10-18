/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.mesalabs.unica.gpu;

import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.util.*;
import org.json.*;

/** Portable values only. Firmware compatibility and all writes stay in the worker. */
final class GpuProfile {
    static final int MAX_BYTES=32768;
    private final JSONObject json;
    final long[] frequencies=new long[10];
    final int[] levels=new int[10];
    final String name;
    private GpuProfile(JSONObject value)throws Exception {
        fields(value,"format","version","device","gpu","table","name","source_firmware","power_levels");
        if(!"unica-konabess-profile".equals(value.get("format"))||integer(value,"version")!=1||!"dm3q".equals(value.get("device"))||!"Adreno740v2".equals(value.get("gpu"))||!"kalama-v2-table0".equals(value.get("table")))throw new IllegalArgumentException("Profile format or target GPU does not match this device");
        name=plain(value,"name",80);plain(value,"source_firmware",256);
        Object entries=value.get("power_levels");if(!(entries instanceof JSONArray)||((JSONArray)entries).length()!=10)throw new IllegalArgumentException("Expected exactly ten power levels");
        long previous=Long.MAX_VALUE;int previousLevel=Integer.MAX_VALUE;
        for(int i=0;i<10;i++){
            JSONObject row=((JSONArray)entries).getJSONObject(i);fields(row,"index","frequency_hz","rpmh_level");
            long f=integer(row,"frequency_hz"),l=integer(row,"rpmh_level");boolean corner=false;for(int c:new int[]{52,56,60,64,80,128,144,192,224})if(l==c)corner=true;
            if(integer(row,"index")!=i||f<124800000||f>850000000||f%100000!=0||f>=previous||!corner||l>previousLevel)throw new IllegalArgumentException("Invalid frequency, voltage level or descending order at power level "+i);
            frequencies[i]=f;levels[i]=(int)l;previous=f;previousLevel=(int)l;
        }
        json=new JSONObject(value.toString());
    }
    private static void fields(JSONObject o,String... names)throws Exception {
        Set<String> allowed=new HashSet<>(Arrays.asList(names));Iterator<String> keys=o.keys();while(keys.hasNext())if(!allowed.contains(keys.next()))throw new IllegalArgumentException("Unknown profile field");
        for(String name:names)if(!o.has(name))throw new IllegalArgumentException("Missing profile field: "+name);
    }
    private static long integer(JSONObject o,String key)throws Exception {Object v=o.get(key);if(!(v instanceof Integer)&&!(v instanceof Long))throw new IllegalArgumentException("Expected whole number: "+key);return ((Number)v).longValue();}
    private static String plain(JSONObject o,String key,int max)throws Exception {Object v=o.get(key);if(!(v instanceof String)||((String)v).length()>max)throw new IllegalArgumentException("Invalid profile text: "+key);String s=(String)v;for(int i=0;i<s.length();i++)if(Character.isISOControl(s.charAt(i)))throw new IllegalArgumentException("Control characters in profile text");return s;}
    static GpuProfile parse(byte[] bytes)throws Exception {
        if(bytes.length==0||bytes.length>MAX_BYTES)throw new IllegalArgumentException("Profile must be 1–32768 bytes");
        String text=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        JSONTokener tokener=new JSONTokener(text);Object value=tokener.nextValue();if(!(value instanceof JSONObject)||tokener.nextClean()!=0)throw new IllegalArgumentException("Expected one JSON profile");return new GpuProfile((JSONObject)value);
    }
    static GpuProfile create(String name,String firmware,long[] f,int[] l)throws Exception {
        if(f.length!=10||l.length!=10)throw new IllegalArgumentException("Expected ten power levels");
        JSONObject root=new JSONObject().put("format","unica-konabess-profile").put("version",1).put("device","dm3q").put("gpu","Adreno740v2").put("table","kalama-v2-table0").put("name",name).put("source_firmware",firmware);
        JSONArray rows=new JSONArray();for(int i=0;i<10;i++)rows.put(new JSONObject().put("index",i).put("frequency_hz",f[i]).put("rpmh_level",l[i]));return new GpuProfile(root.put("power_levels",rows));
    }
    byte[] encode()throws Exception {return (json.toString(2)+"\n").getBytes(StandardCharsets.UTF_8);}
}
