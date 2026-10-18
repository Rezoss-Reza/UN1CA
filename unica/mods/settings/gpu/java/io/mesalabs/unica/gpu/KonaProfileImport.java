/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.mesalabs.unica.gpu;

import java.io.*;
import java.util.*;
import java.util.regex.*;
import java.util.zip.GZIPInputStream;
import org.json.*;

/** Bounded data parser for upstream exports; never invokes dtc or a shell. */
final class KonaProfileImport {
    private static final class Node {String name;final Map<String,long[]> properties=new LinkedHashMap<>();final List<Node> children=new ArrayList<>();}
    private final ArrayList<String> tokens=new ArrayList<>();private int position;
    private KonaProfileImport(String text){
        Pattern pattern=Pattern.compile("\\s+|0[xX][0-9a-fA-F]+|[0-9]+|[A-Za-z_#][A-Za-z0-9_,#@-]*|[{}=<>;]");Matcher m=pattern.matcher(text);int end=0;
        while(m.find()){if(m.start()!=end)throw new IllegalArgumentException("Unsupported content in KonaBess device-tree data");end=m.end();if(!m.group().trim().isEmpty())tokens.add(m.group());if(tokens.size()>8192)throw new IllegalArgumentException("Too many device-tree tokens");}if(end!=text.length())throw new IllegalArgumentException("Invalid device-tree suffix");
    }
    private String next(){if(position>=tokens.size())throw new IllegalArgumentException("Truncated KonaBess table");return tokens.get(position++);}
    private void expect(String s){if(!next().equals(s))throw new IllegalArgumentException("Expected "+s+" in KonaBess table");}
    private Node node(int depth){
        if(depth>1)throw new IllegalArgumentException("Unexpected nested GPU node");Node n=new Node();n.name=next();expect("{");
        while(!tokens.get(position).equals("}")){
            if(position+1>=tokens.size())throw new IllegalArgumentException("Truncated GPU property");
            if(tokens.get(position+1).equals("{")){if(n.children.size()>=15)throw new IllegalArgumentException("GPU table exceeds 15 levels");n.children.add(node(depth+1));continue;}
            String key=next();expect("=");expect("<");ArrayList<Long> values=new ArrayList<>();
            for(String value=next();!value.equals(">");value=next()){
                if(!value.matches("0[xX][0-9a-fA-F]+|[0-9]+")||value.length()>12||values.size()>=16)throw new IllegalArgumentException("Invalid device-tree cell");long v=value.startsWith("0x")||value.startsWith("0X")?Long.parseLong(value.substring(2),16):Long.parseLong(value);if(v>0xffffffffL)throw new IllegalArgumentException("Device-tree cell overflows u32");values.add(v);
            }expect(";");long[] array=new long[values.size()];for(int i=0;i<array.length;i++)array[i]=values.get(i);if(n.properties.put(key,array)!=null)throw new IllegalArgumentException("Duplicate GPU property");
        }expect("}");expect(";");return n;
    }
    private static long cell(Node n,String key){long[] v=n.properties.get(key);if(v==null||v.length!=1)throw new IllegalArgumentException("Missing or malformed "+key);return v[0];}
    private static void allowed(Node n,String... names){Set<String> allowed=new HashSet<>(Arrays.asList(names));for(String key:n.properties.keySet())if(!allowed.contains(key))throw new IllegalArgumentException("Unsupported GPU property: "+key);}
    static GpuProfile parse(String export)throws Exception {
        byte[] compressed;try{compressed=Base64.getDecoder().decode(export.substring("konabess://".length()));}catch(IllegalArgumentException e){throw new IllegalArgumentException("Invalid KonaBess Base64 data");}
        ByteArrayOutputStream output=new ByteArrayOutputStream();try(InputStream in=new GZIPInputStream(new ByteArrayInputStream(compressed))){byte[] bytes=new byte[4096];int n;while((n=in.read(bytes))!=-1){if(output.size()+n>GpuProfile.MAX_BYTES)throw new IllegalArgumentException("Expanded KonaBess profile exceeds 32 KiB");output.write(bytes,0,n);}}
        JSONObject o=GpuProfile.object(GpuProfile.decode(output.toByteArray()));
        Set<String> keys=new HashSet<>(Arrays.asList("chip","desc","freq","volt","model","brand","id","version","fingerprint","manufacturer","device","name","board"));Iterator<String> iterator=o.keys();while(iterator.hasNext())if(!keys.contains(iterator.next()))throw new IllegalArgumentException("Unknown KonaBess export field");
        if(!"kalama".equals(o.get("chip")))throw new IllegalArgumentException("This KonaBess export is not for kalama");
        if(o.has("volt")&&!"".equals(o.get("volt")))throw new IllegalArgumentException("Separate voltage/OPP tables are not supported for this Samsung firmware");
        Object text=o.get("freq");if(!(text instanceof String))throw new IllegalArgumentException("Missing KonaBess frequency table");KonaProfileImport parser=new KonaProfileImport((String)text);JSONArray bins=new JSONArray();
        for(int id=0;id<2;id++){
            Node bin=parser.node(0);if(!bin.name.equals("qcom,gpu-pwrlevels-"+id))throw new IllegalArgumentException("Expected GPU tables 0 then 1");allowed(bin,"qcom,initial-pwrlevel","qcom,initial-min-pwrlevel","qcom,sku-codes","#size-cells","#address-cells");
            long[] sku=id==0?new long[]{3,6,0x300f1,0x300f3,0x500f1,0x500f3,0x700f1,0x700f2,0x700fe,0x700ff}:new long[]{0};
            if(!Arrays.equals(bin.properties.get("qcom,sku-codes"),sku)||cell(bin,"#size-cells")!=0||cell(bin,"#address-cells")!=1)throw new IllegalArgumentException("KonaBess SKU selection differs from this Samsung firmware");
            JSONArray rows=new JSONArray();for(int i=0;i<bin.children.size();i++){
                Node n=bin.children.get(i);if(!n.name.equals("qcom,gpu-pwrlevel@"+i)||!n.children.isEmpty()||cell(n,"reg")!=i)throw new IllegalArgumentException("Non-contiguous KonaBess GPU index");allowed(n,"reg","qcom,gpu-freq","qcom,level","qcom,bus-freq","qcom,bus-min","qcom,bus-max","qcom,acd-level");
                rows.put(new JSONObject().put("index",i).put("frequency_hz",cell(n,"qcom,gpu-freq")).put("rpmh_level",cell(n,"qcom,level")).put("bus_frequency",cell(n,"qcom,bus-freq")).put("bus_minimum",cell(n,"qcom,bus-min")).put("bus_maximum",cell(n,"qcom,bus-max")).put("acd_level",n.properties.containsKey("qcom,acd-level")?cell(n,"qcom,acd-level"):JSONObject.NULL));
            }
            bins.put(new JSONObject().put("id",id).put("initial_level",cell(bin,"qcom,initial-pwrlevel")).put("minimum_level",bin.properties.containsKey("qcom,initial-min-pwrlevel")?cell(bin,"qcom,initial-min-pwrlevel"):bin.children.size()-1).put("power_levels",rows));
        }
        if(parser.position!=parser.tokens.size())throw new IllegalArgumentException("Unexpected extra KonaBess data");Object desc=o.get("desc");if(!(desc instanceof String))throw new IllegalArgumentException("Invalid KonaBess description");return GpuProfile.fromTables((String)desc,"Unknown; imported upstream KonaBess export",bins);
    }
}
