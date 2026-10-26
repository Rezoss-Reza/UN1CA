/* Copyright (c) 2026 Rezoss
 * SPDX-License-Identifier: GPL-3.0-or-later */
package io.mesalabs.unica.keybox;

import org.json.*;
import java.util.*;

public final class ManageKeyboxTest {
    private static void rejected(JSONObject root)throws Exception {
        try{Profiles.validate(root);}catch(Exception expected){return;}throw new AssertionError("Invalid configuration accepted");
    }
    public static void main(String[] args)throws Exception {
        JSONObject root=Profiles.defaults();Profiles.validate(root);
        if(!root.getJSONArray("profiles").getJSONObject(0).getString("scope").equals("all"))throw new AssertionError();
        root.getJSONArray("profiles").put(Profiles.profile("second"));Profiles.validate(root);
        root.getJSONArray("profiles").getJSONObject(1).put("scope","all");rejected(root);
        root=Profiles.defaults();root.getJSONArray("profiles").getJSONObject(0).put("id","../bad");rejected(root);
        root=Profiles.defaults();root.getJSONArray("profiles").getJSONObject(0).put("keybox","missing");rejected(root);
        root=Profiles.defaults();root.getJSONArray("profiles").getJSONObject(0).put("mode","bad");rejected(root);
        root=Profiles.defaults();root.getJSONArray("profiles").getJSONObject(0).put("patchLevel",new JSONObject().put("system","2026-02-31"));rejected(root);
        root=Profiles.defaults();root.getJSONObject("keyboxes").put("named","synthetic");root.getJSONArray("profiles").getJSONObject(0).put("keybox","named");Profiles.validate(root);
        if(!Profiles.xml(root,root.getJSONArray("profiles").getJSONObject(0),"other").equals("synthetic"))throw new AssertionError();
        String secret="SECRET-KEY-MATERIAL serial=private imei=private alias=private com.private.app";
        String event=Diagnostics.sanitize("I/TEESimulator( 123): dmn Control: ack epoch=123 ok=true "+secret);
        if(!event.equals("I · Connection · Configuration acknowledged")||event.contains("SECRET"))throw new AssertionError("Redaction failure");
        String unknown=Diagnostics.sanitize("E/TEESimulator( 123): "+secret);
        if(!unknown.equals("E · Backend · Backend event (details redacted)"))throw new AssertionError();
        if(Diagnostics.sanitize("I/Other( 123): "+secret)!=null)throw new AssertionError();
        if(Diagnostics.filter(Arrays.asList(event,unknown),"E","All","").contains("acknowledged"))throw new AssertionError();
        if(!Diagnostics.filter(Arrays.asList(event,unknown),"V","Connection","acknowledged").contains("acknowledged"))throw new AssertionError();
        System.out.println("Manage Keybox tests passed: schema validation, named keyboxes, strict event redaction and filters");
    }
}
