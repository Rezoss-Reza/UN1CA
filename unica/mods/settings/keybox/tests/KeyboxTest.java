/* Copyright (c) 2026 Rezoss
 * SPDX-License-Identifier: GPL-3.0-or-later */
package io.mesalabs.unica.keybox;

import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.io.*;

/** Uses public AOSP test material only; never reads an imported device keybox. */
public final class KeyboxTest {
    private static void rejected(String xml, boolean aosp) throws Exception {
        try { Keybox.parse(xml,aosp); } catch (Exception expected) { return; }
        throw new AssertionError("Invalid keybox accepted");
    }
    public static void main(String[] args) throws Exception {
        String xml=new String(Files.readAllBytes(Paths.get(args[0])),StandardCharsets.UTF_8);
        Keybox both=Keybox.parse(xml,true);
        if(both.certificates.size()!=4)throw new AssertionError("Missing certificates");
        if(both.revoked(Collections.emptySet()))throw new AssertionError("False revocation");
        for(java.security.cert.X509Certificate cert:both.certificates) {
            if(!both.revoked(Collections.singleton(cert.getSerialNumber().toString(16))))throw new AssertionError("Missed revocation");
        }
        rejected(xml,false); // Public AOSP roots cannot impersonate Google roots.
        rejected("<!DOCTYPE AndroidAttestation [<!ENTITY x SYSTEM 'file:///etc/passwd'>]>"+xml,true);
        rejected(xml.replace("<NumberOfKeyboxes>1</NumberOfKeyboxes>","<NumberOfKeyboxes>2</NumberOfKeyboxes>"),true);
        rejected(xml.replaceFirst("<NumberOfCertificates>2</NumberOfCertificates>","<NumberOfCertificates>1</NumberOfCertificates>"),true);
        rejected(xml.replaceFirst("algorithm=\"ecdsa\"","algorithm=\"invalid\""),true);
        java.security.KeyPairGenerator generator=java.security.KeyPairGenerator.getInstance("EC");
        generator.initialize(256);
        String wrongKey="-----BEGIN PRIVATE KEY-----\n"+Base64.getEncoder().encodeToString(generator.generateKeyPair().getPrivate().getEncoded())+"\n-----END PRIVATE KEY-----";
        rejected(xml.replaceFirst("(?s)-----BEGIN EC PRIVATE KEY-----.*?-----END EC PRIVATE KEY-----",wrongKey),true);
        String ecOnly=xml.replaceAll("(?s)<Key algorithm=\"rsa\">.*?</Key>","");
        if(Keybox.parse(ecOnly,true).certificates.size()!=2)throw new AssertionError("EC-only regression");
        String rsaOnly=xml.replaceAll("(?s)<Key algorithm=\"ecdsa\">.*?</Key>","");
        if(Keybox.parse(rsaOnly,true).certificates.size()!=2)throw new AssertionError("RSA regression");
        try { Keybox.read(new ByteArrayInputStream(new byte[Keybox.MAX_BYTES+1]),Keybox.MAX_BYTES);throw new AssertionError("Oversized input accepted"); }
        catch(IOException expected) { }
        System.out.println("Keybox validation tests passed (public AOSP, EC-only, RSA, trust roots, chains, revocation, entities and limits)");
    }
}
