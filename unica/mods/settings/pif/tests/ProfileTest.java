/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.mesalabs.unica.pif;
import java.util.*;
public final class ProfileTest {
    private static final String FP="FINGERPRINT=google/bluejay_beta/bluejay:CANARY/ZP11.260918.007/16484274:user/release-keys\n";
    private static int checks;
    private static void check(boolean value){checks++;if(!value)throw new AssertionError();}
    private static void rejects(String text)throws Exception{try{Profile.parse(text,false);throw new AssertionError("Accepted invalid profile");}catch(IllegalArgumentException|java.time.format.DateTimeParseException expected){checks++;}}
    public static void main(String[] args)throws Exception{
        Map<String,String> current=Profile.parse(FP+"MODEL=Pixel 6a\nspoofBuild=false\nspoofProps=true\n",false);
        check(Profile.parse(Profile.format(current),false).equals(current));
        Map<String,String> fetched=Profile.parse(FP+"MODEL=Pixel 6a\nspoofBuild=true\n",true);
        Map<String,String> merged=Profile.replacement(fetched,current);
        check(merged.get("spoofBuild").equals("false"));
        check(merged.get("spoofProps").equals("true"));
        check(merged.get("spoofVendingBuild").equals("false"));
        check(merged.get("PRODUCT").equals("null"));
        rejects(FP+"MODEL=one\nMODEL=two\n");rejects(FP+"spoofProvider=true\n");rejects(FP+"spoofBuild=foo\n");
        rejects(FP+"SECURITY_PATCH=2026-02-30\n");rejects(FP+"DEVICE_INITIAL_SDK_INT=0\n");
        rejects("FINGERPRINT=bad\n");rejects(FP+"MODEL="+String.join("",Collections.nCopies(46,"é"))+"\n");
        rejects(FP+"MODEL=line\tbreak\n");rejects(FP+"unknown=value\n");
        check(Profile.parse(FP+"SECURITY_PATCH=null\nDEVICE_INITIAL_SDK_INT=null\n",false).size()==3);
        System.out.println("PIF profile checks passed: "+checks);
    }
}
