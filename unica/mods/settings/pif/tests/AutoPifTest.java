/* Copyright (c) 2026 Rezoss
 * SPDX-License-Identifier: GPL-3.0-or-later */
package io.mesalabs.unica.pif;

import java.io.IOException;
import java.util.*;
import org.json.*;

public final class AutoPifTest {
    private static int checks;
    private static final String FLASH="<body data-client-config=\"%.@.1,&quot;AIza00000000000000000000000000000000000&quot;\">";
    private static final String BULLETIN="<tr><td><a href=\"/docs/security/bulletin/pixel/2026/2026-10-01\">October</a></td><td>2026-10-05</td></tr>";
    private static void check(boolean value){checks++;if(!value)throw new AssertionError("check "+checks);}
    private interface Task {void run()throws Exception;}
    private static void rejects(Task task)throws Exception{try{task.run();throw new AssertionError("Accepted invalid response");}catch(IOException|JSONException|IllegalArgumentException expected){checks++;}}
    private static JSONObject build(String product,String number,String id,String month,boolean canary)throws Exception{
        return new JSONObject().put("product",product).put("buildId",number).put("releaseCandidateName",id)
            .put("previewMetadata",new JSONObject().put("id",month).put("canary",canary));
    }
    private static String builds()throws Exception{
        // Deliberately unordered; neither the last element nor a non-Canary build may win.
        return new JSONObject().put("flashstationBuild",new JSONArray()
            .put(build("bluejay_beta","16484274","ZP11.260918.007","canary-202610",true))
            .put(build("bluejay_beta","99999999","OTHER","beta-202611",false))
            .put(build("shiba_beta","99999998","OTHER","canary-202611",true))
            .put(build("bluejay_beta","16290768","ZP11.260821.010","canary-202609",true))).toString();
    }
    private static AutoPif.Fetcher fake(String flash,String builds,String bulletin,boolean unavailable){
        return (url,referer,limit)->{
            if(url.equals("https://flash.android.com"))return flash;
            if(url.startsWith("https://content-flashstation-pa.googleapis.com/")){
                check("https://flash.android.com".equals(referer));return builds;
            }
            if(unavailable)throw new IOException("Bulletin unavailable");return bulletin;
        };
    }
    public static void main(String[] args)throws Exception{
        String builds=builds();
        AutoPif.Result exact=AutoPif.fetch("Pixel 6a","bluejay_beta",fake(FLASH,builds,BULLETIN,false));
        check(!exact.inferredPatch);check(exact.profile.get("SECURITY_PATCH").equals("2026-10-05"));
        check(exact.profile.get("FINGERPRINT").contains("ZP11.260918.007/16484274"));
        check(exact.profile.get("DEVICE_INITIAL_SDK_INT").equals("32"));
        AutoPif.Result missing=AutoPif.fetch("Pixel 6a","bluejay_beta",fake(FLASH,builds,"<table></table>",false));
        check(missing.inferredPatch&&missing.profile.get("SECURITY_PATCH").equals("2026-10-05"));
        AutoPif.Result offline=AutoPif.fetch("Pixel 6a","bluejay_beta",fake(FLASH,builds,"",true));check(offline.inferredPatch);
        rejects(()->AutoPif.fetch("Pixel 6a","bluejay_beta",fake("changed format",builds,BULLETIN,false)));
        rejects(()->AutoPif.fetch("Pixel 6a","bluejay_beta",fake(FLASH,"{invalid",BULLETIN,false)));
        rejects(()->AutoPif.latest("{\"flashstationBuild\":[]}","bluejay_beta"));
        rejects(()->AutoPif.fetch("Pixel 6a","../bluejay_beta",fake(FLASH,builds,BULLETIN,false)));
        rejects(()->AutoPif.fetch("Pixel new","unknown_beta",fake(FLASH,builds,BULLETIN,false)));
        String invalid=builds.replace("ZP11.260918.007","invalid/identity");
        rejects(()->AutoPif.fetch("Pixel 6a","bluejay_beta",fake(FLASH,invalid,BULLETIN,false)));
        Map<String,String> current=new LinkedHashMap<>(exact.profile);current.put("spoofBuild","false");current.put("spoofProps","true");current.put("spoofVendingBuild","true");
        Map<String,String> replaced=Profile.replacement(exact.profile,current);
        check(replaced.get("spoofBuild").equals("false")&&replaced.get("spoofProps").equals("true")&&replaced.get("spoofVendingBuild").equals("true"));
        check(current.equals(replaced)==false);check(!exact.profile.containsKey("spoofBuild"));
        System.out.println("Autopif fixture checks passed: "+checks);
        if(args.length>0&&args[0].equals("--live")){
            for(String product:new String[]{"bluejay_beta","shiba_beta"}){
                String model=product.equals("bluejay_beta")?"Pixel 6a":"Pixel 8";
                AutoPif.Result result=AutoPif.fetch(model,product);
                Profile.validate(result.profile);
                Map<String,String> github=Profile.parse(PixelSource.get("device_prop/"+product+".prop"),true);
                System.out.println(model+": live Autopif and GitHub validated; same fingerprint="+result.profile.get("FINGERPRINT").equals(github.get("FINGERPRINT"))+"; inferred SPL="+result.inferredPatch);
            }
        }
    }
}
