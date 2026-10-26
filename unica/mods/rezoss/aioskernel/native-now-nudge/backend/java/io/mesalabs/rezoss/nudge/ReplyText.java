package io.mesalabs.rezoss.nudge;

import java.util.regex.Pattern;

/** Remove known generated tone/speaker markers only at reply boundaries. */
public final class ReplyText {
    private static final String LABEL="(?:me|user|assistant|friend|others|positive|postive|uncertain|negative|neutral)\\s*:";
    private static final Pattern START=Pattern.compile("^"+LABEL+"\\s*",Pattern.CASE_INSENSITIVE);
    private static final Pattern END=Pattern.compile("(?:^|\\s+)"+LABEL+"\\s*$",Pattern.CASE_INSENSITIVE);
    public static String clean(String value) {
        String text=value.trim(),previous;
        do {
            previous=text;
            text=text.replaceFirst("^(?:[-*•]|[0-9]+[.)])\\s+", "").trim();
            if(text.length()>=2&&text.startsWith("\"")&&text.endsWith("\""))text=text.substring(1,text.length()-1).trim();
            text=START.matcher(text).replaceFirst("");
            text=END.matcher(text).replaceFirst("").trim();
        } while(!text.equals(previous));
        return text;
    }
    static String lines(String value) {
        StringBuilder output=new StringBuilder();
        for(String line:value.split("\\R")) {
            String text=clean(line);
            if(!text.isEmpty()){if(output.length()>0)output.append('\n');output.append(text);}
        }
        return output.toString();
    }
}
