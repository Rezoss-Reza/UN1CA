package io.mesalabs.rezoss.nudge;

/** Remove generated speaker markers only at reply boundaries. */
public final class ReplyText {
    public static String clean(String value) {
        String text=value.trim().replaceFirst("^(?:[-*•]|[0-9]+[.)])\\s+", "").trim();
        if(text.length()>=2&&text.startsWith("\"")&&text.endsWith("\""))text=text.substring(1,text.length()-1).trim();
        for(int i=0;i<3;i++)text=text.replaceFirst("(?i)^me\\s*:\\s*", "")
            .replaceFirst("(?i)(?:^|\\s+)me\\s*:\\s*$", "").trim();
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
