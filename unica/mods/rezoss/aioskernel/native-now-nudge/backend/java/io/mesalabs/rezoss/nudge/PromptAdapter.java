package io.mesalabs.rezoss.nudge;
import java.util.regex.*;
/** Translate the known Gauss contextual-reply template to a short Qwen instruction. */
public final class PromptAdapter {
 public static String adapt(String raw){
  String dialogue=section(raw,"dialogue"),records=section(raw,"app_records");
  Matcher language=Pattern.compile("Output only the three responses in ([^\\r\\n,]+), separated by line breaks").matcher(raw);
  if(raw.contains("generate three natural responses")&&dialogue!=null&&language.find()){
   StringBuilder prompt=new StringBuilder("Suggest three short text messages I can send back, in ").append(language.group(1).trim()).append(".\nConversation:\n");
   prompt.append(dialogue.trim().replace("Others:","Friend:").replace("User:","Me:"));
   if(records!=null&&!records.trim().isEmpty()&&!records.trim().equals("[]"))prompt.append("\nRelevant context:\n").append(records.trim());
   prompt.append("\nWrite one positive reply, one uncertain reply, and one negative reply appropriate to the conversation. Do not repeat the question. Output only the three replies on separate lines.\n/no_think");
   return prompt.toString();
  }
  return raw.replace("<|begin_of_text|>","").replace("<|turn_start|>System","")
   .replace("<|turn_start|>User","").replace("<|turn_start|>Assistant","").replace("<|turn_end|>","").trim()+"\n/no_think";
 }
 private static String section(String raw,String name){
  String begin="<"+name+">",end="</"+name+">";int first=raw.indexOf(begin),last=raw.lastIndexOf(end);
  return first>=0&&last>=first+begin.length()?raw.substring(first+begin.length(),last):null;
 }
}
