package io.mesalabs.unica.ota;
public class RulesTest {
 interface Call{void run() throws Exception;}
 static void reject(Call call,String text)throws Exception{try{call.run();throw new AssertionError("Accepted invalid update");}catch(java.io.IOException e){if(!e.getMessage().contains(text))throw e;}}
 public static void main(String[] args)throws Exception{
  OtaRules.compatible("dm3q","100","100","200");
  reject(()->OtaRules.compatible("dm3q","200","100","200"),"already applied");
  reject(()->OtaRules.compatible("dm3q","99","100","200"),"full ROM");
  reject(()->OtaRules.compatible("dm3q","201","100","200"),"full ROM");
  reject(()->OtaRules.compatible("dm2q","100","100","200"),"dm3q");
  reject(()->OtaRules.compatible("dm3q","100","200","100"),"Invalid");
  reject(()->OtaRules.compatible("dm3q","100","100","100"),"Invalid");
  System.out.println("PASS: exact base, already applied, wrong base/device and downgrade rules");
 }
}
