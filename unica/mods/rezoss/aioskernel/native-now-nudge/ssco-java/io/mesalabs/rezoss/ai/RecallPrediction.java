package io.mesalabs.rezoss.ai;

import java.util.*;

/** Wire-format validation only. Intent and parameter inference belong to the model. */
public final class RecallPrediction {
    public final String action, parameters;
    private RecallPrediction(String action, String parameters) { this.action=action; this.parameters=parameters; }
    public static RecallPrediction parse(String input, String output) {
        if(input==null||output==null||input.length()>12000||output.length()>2048)return null;
        int first=0,last=output.length();
        while(first<last&&" \r\n".indexOf(output.charAt(first))>=0)first++;
        while(last>first&&" \r\n".indexOf(output.charAt(last-1))>=0)last--;
        String line=output.substring(first,last);
        if(line.equals("Unsafe")||line.equals("Unsafe\t"))return new RecallPrediction("Unsafe", "");
        if(line.indexOf('\n')>=0||line.indexOf('\r')>=0)return null;
        int tab=line.indexOf('\t');
        if(tab<1||tab!=line.lastIndexOf('\t'))return null;
        String action=line.substring(0,tab), raw=line.substring(tab+1);
        if(action.equals("View Calendar")&&raw.isEmpty())return new RecallPrediction(action, "");
        Set<String> allowed;
        if(action.equals("Share Photo"))allowed=new HashSet<>(Arrays.asList("photo"));
        else if(action.equals("Create Calendar"))allowed=new HashSet<>(Arrays.asList("type","title","start","end","location","per"));
        else if(action.equals("View Calendar"))allowed=new HashSet<>(Arrays.asList("start"));
        else return null;
        Map<String,String> fields=new LinkedHashMap<>();
        for(String segment:raw.split("\\|",-1)) {
            int space=segment.indexOf(' ');
            if(space<1)return null;
            String key=segment.substring(0,space),value=segment.substring(space+1).trim();
            if(!allowed.contains(key)||fields.containsKey(key)||value.isEmpty()||value.length()>256)return null;
            // The stock schema checks names, but does not ground photo/title/start.
            if(!key.equals("type")&&!key.equals("per")&&!input.toLowerCase(Locale.ROOT).contains(value.toLowerCase(Locale.ROOT)))return null;
            if(key.equals("type")&&!value.equals("event"))return null;
            fields.put(key,value);
        }
        if(action.equals("Share Photo")&&!fields.containsKey("photo"))return null;
        if(action.equals("Create Calendar")&&!fields.keySet().containsAll(Arrays.asList("type","title","start")))return null;
        StringJoiner joined=new StringJoiner("|");
        for(Map.Entry<String,String> field:fields.entrySet())joined.add(field.getKey()+" "+field.getValue());
        return new RecallPrediction(action,joined.toString());
    }
}
