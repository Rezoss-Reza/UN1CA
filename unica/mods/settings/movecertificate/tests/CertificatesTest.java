// SPDX-License-Identifier: GPL-3.0-or-later
package io.mesalabs.unica.movecertificate;
import java.io.*;
import java.nio.file.*;
import java.security.cert.*;
import java.util.*;

public final class CertificatesTest {
    interface Job{void run()throws Exception;}
    static void rejects(Job job)throws Exception{boolean rejected=false;try{job.run();}catch(Exception expected){rejected=true;}if(!rejected)throw new AssertionError("Accepted invalid input");}
    public static void main(String[] args)throws Exception{
        byte[] data=Files.readAllBytes(Paths.get(args[0]));X509Certificate c=Certificates.parse(data);
        if(!Certificates.hash(c).equals(args[1]))throw new AssertionError("Android subject hash mismatch");
        if(!Arrays.equals(c.getEncoded(),Certificates.parse(Certificates.pem(c)).getEncoded()))throw new AssertionError("PEM round trip");
        if(!Arrays.equals(c.getEncoded(),Certificates.parse(c.getEncoded()).getEncoded()))throw new AssertionError("DER round trip");
        rejects(()->Certificates.read(new ByteArrayInputStream(new byte[Certificates.MAX_BYTES+1])));
        rejects(()->Certificates.parse("invalid".getBytes("UTF-8")));
        byte[] pem=Certificates.pem(c),two=new byte[pem.length*2];System.arraycopy(pem,0,two,0,pem.length);System.arraycopy(pem,0,two,pem.length,pem.length);
        rejects(()->Certificates.parse(two));
        Path temp=Files.createTempDirectory("unica-movecert-test");
        Certificates.install(temp.toFile(),c);rejects(()->Certificates.install(temp.toFile(),c));
        if(Certificates.files(temp.toFile()).length!=1)throw new AssertionError("Duplicate import");
        for(int i=2;i<args.length;i++){final byte[] invalid=Files.readAllBytes(Paths.get(args[i]));rejects(()->{X509Certificate x=Certificates.parse(invalid);x.checkValidity();});}
        System.out.println("PASS CA validation, PEM/DER, Android hash, size bounds, duplicate rejection and invalid fixtures");
    }
}
