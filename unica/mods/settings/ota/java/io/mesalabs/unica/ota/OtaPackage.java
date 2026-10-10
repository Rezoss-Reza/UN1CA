/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.mesalabs.unica.ota;
import org.json.*;
import java.io.*;
import java.security.*;
import java.security.spec.*;
import java.util.*;
import java.util.zip.*;
import android.util.Base64;

final class OtaPackage {
 static String prop(String key) throws Exception {return (String)Class.forName("android.os.SystemProperties").getMethod("get",String.class).invoke(null,key);}
 static JSONObject signed(byte[] envelope) throws Exception {
  JSONObject outer=new JSONObject(new String(envelope,"UTF-8"));
  byte[] payload=Base64.decode(outer.getString("payload"),Base64.DEFAULT);
  PublicKey key=KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(Base64.decode(OtaTrust.KEY,Base64.DEFAULT)));
  Signature s=Signature.getInstance("SHA256withRSA");s.initVerify(key);s.update(payload);
  if(!s.verify(Base64.decode(outer.getString("signature"),Base64.DEFAULT)))throw new IOException("Update publisher signature is invalid");
  JSONObject result=new JSONObject(new String(payload,"UTF-8"));
  if(result.getInt("schema")!=1||!"dm3q".equals(result.getString("device")))throw new IOException("This update is for a different device");
  return result;
 }
 static byte[] read(InputStream stream,int limit) throws Exception {
  ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] b=new byte[8192];int n;
  while((n=stream.read(b))!=-1){if(out.size()+n>limit)throw new IOException("Metadata is too large");out.write(b,0,n);}return out.toByteArray();
 }
 static String hash(InputStream f) throws Exception {
  MessageDigest d=MessageDigest.getInstance("SHA-256");byte[] b=new byte[1024*1024];int n;while((n=f.read(b))!=-1)d.update(b,0,n);
  StringBuilder out=new StringBuilder();for(byte v:d.digest())out.append(String.format(java.util.Locale.ROOT,"%02x",v&255));return out.toString();
 }
 static void compatible(JSONObject m) throws Exception {
  OtaRules.compatible(prop("ro.unica.device"),prop("ro.unica.timestamp"),m.getString("base_timestamp"),m.getString("target_timestamp"));
 }
 static JSONObject verify(File file) throws Exception {
  try(ZipFile z=new ZipFile(file)){
   ZipEntry metadata=z.getEntry("unica/ota/update.json");if(metadata==null)throw new IOException("This ZIP has no signed updater metadata. Use a newly generated OTA.");
   JSONObject m;try(InputStream in=z.getInputStream(metadata)){m=signed(read(in,4*1024*1024));}compatible(m);
   JSONObject entries=m.getJSONObject("entries");Set<String> seen=new HashSet<>();Enumeration<? extends ZipEntry> all=z.entries();
   while(all.hasMoreElements()){
    ZipEntry e=all.nextElement();String name=e.getName();if(!seen.add(name))throw new IOException("Duplicate package entry");
    if(e.isDirectory()||name.equals("unica/ota/update.json")||signatureFile(name))continue;
    if(!entries.has(name))throw new IOException("Unsigned package entry: "+name);
    try(InputStream in=z.getInputStream(e)){if(!hash(in).equals(entries.getString(name)))throw new IOException("Package checksum failed: "+name);}
   }
   Iterator<String> keys=entries.keys();while(keys.hasNext())if(!seen.contains(keys.next()))throw new IOException("Missing signed package entry");
   return m;
  }
 }
 static boolean signatureFile(String n){String u=n.toUpperCase(Locale.ROOT);return u.startsWith("META-INF/")&&(u.endsWith(".SF")||u.endsWith(".RSA")||u.endsWith(".DSA")||u.endsWith(".EC")||u.equals("META-INF/MANIFEST.MF"));}
}
