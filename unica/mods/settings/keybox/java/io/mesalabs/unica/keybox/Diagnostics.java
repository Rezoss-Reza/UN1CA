/* Copyright (c) 2026 Rezoss
 * SPDX-License-Identifier: GPL-3.0-or-later */
package io.mesalabs.unica.keybox;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.*;

/** Allowlisted event summaries only. Never returns raw messages, packages, aliases or key data. */
final class Diagnostics {
    private static final Pattern HEADER=Pattern.compile("^([VDIWEF])/TEESimulator\\s*\\(\\s*\\d+\\):\\s*(.*)$");
    static List<String> read()throws Exception {
        Process process=new ProcessBuilder("/system/bin/logcat","-d","-t","400","-v","brief","TEESimulator:V","*:S").redirectErrorStream(true).start();
        java.util.concurrent.ExecutorService executor=java.util.concurrent.Executors.newSingleThreadExecutor();
        try{
            java.util.concurrent.Future<byte[]> result=executor.submit(()->Keybox.read(process.getInputStream(),512*1024));
            byte[] bytes=result.get(8,TimeUnit.SECONDS);
            if(!process.waitFor(2,TimeUnit.SECONDS)||process.exitValue()!=0)throw new IOException("Logs unavailable");
            List<String> rows=new ArrayList<>();
            for(String line:new String(bytes,StandardCharsets.UTF_8).split("\\n")){String safe=sanitize(line);if(safe!=null)rows.add(safe);}
            return rows;
        }finally{process.destroyForcibly();executor.shutdownNow();}
    }
    static String sanitize(String line){
        Matcher m=HEADER.matcher(line);if(!m.matches())return null;
        String raw=m.group(2),event="Backend event (details redacted)";
        String area="Backend";
        if(raw.startsWith("dmn RomApp:")){area="Configuration";
            if(raw.contains("config queued"))event="Configuration queued";
            else if(raw.contains("refresh failed"))event="Configuration refresh failed; new interception disabled";
            else if(raw.contains("bridge started"))event="Settings bridge started";
        }else if(raw.startsWith("dmn Control:")){area="Connection";
            if(raw.contains("ack epoch="))event=raw.contains("ok=true")?"Configuration acknowledged":"Configuration rejected";
            else if(raw.contains("connected to"))event="Interceptor connection established";
            else if(raw.contains("disconnect")||raw.contains("connection lost"))event="Interceptor disconnected";
            else if(raw.contains("hello"))event="Interceptor handshake received";
        }else if(raw.startsWith("dmn Injector:")||raw.startsWith("inj ")){area="Injector";event="Injection event (details redacted)";}
        else if(raw.startsWith("dmn Harvester:")){area="Harvest";event="Hardware harvest event (details redacted)";}
        else if(raw.startsWith("km")||raw.startsWith("ta/")){area="Attestation";event="Attestation event (details redacted)";}
        return m.group(1)+" · "+area+" · "+event;
    }
    static String filter(List<String> rows,String severity,String area,String query){
        StringBuilder result=new StringBuilder();String ranks="VDIWEF";
        for(String row:rows)if(ranks.indexOf(row.charAt(0))>=ranks.indexOf(severity)&&
            (area.equals("All")||row.contains(" · "+area+" · "))&&row.toLowerCase(Locale.ROOT).contains(query.toLowerCase(Locale.ROOT)))result.append(row).append('\n');
        return result.length()==0?"No matching backend events.\n":result.toString();
    }
}
