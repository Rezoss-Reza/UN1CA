/* Copyright (c) 2026 Rezoss
 * SPDX-License-Identifier: GPL-3.0-or-later */
package io.mesalabs.unica.pif;

import javax.net.ssl.HttpsURLConnection;
import java.io.*;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** Same bot data source as KOWX712's inject_s WebUI; never downloads executable code. */
public final class PixelSource {
    private static final String BASE="https://raw.githubusercontent.com/KOWX712/PlayIntegrityFix/bot/";
    public static String get(String path) throws Exception {
        if(!path.equals("device_list.json")&&!path.matches("device_prop/[a-z0-9_]+\\.prop"))throw new IOException("Invalid profile path");
        HttpsURLConnection connection=(HttpsURLConnection)new URL(BASE+path).openConnection();
        connection.setConnectTimeout(12000);connection.setReadTimeout(15000);connection.setInstanceFollowRedirects(false);
        try {
            if(connection.getResponseCode()!=200)throw new IOException("Profile server returned HTTP "+connection.getResponseCode());
            try(InputStream input=connection.getInputStream();ByteArrayOutputStream out=new ByteArrayOutputStream()) {
                byte[] buffer=new byte[4096];int size;
                while((size=input.read(buffer))!=-1){out.write(buffer,0,size);if(out.size()>131072)throw new IOException("Profile response too large");}
                return new String(out.toByteArray(),StandardCharsets.UTF_8);
            }
        } finally {connection.disconnect();}
    }
}
