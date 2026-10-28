// SPDX-License-Identifier: GPL-3.0-or-later
package io.mesalabs.unica.movecertificate;
import java.nio.file.*;
import java.security.cert.X509Certificate;

public final class AdcertSelectionTest {
    public static void main(String[] args)throws Exception{
        Path dir=Files.createTempDirectory("unica-adcert-selection");
        if(AdcertController.personal(dir.toFile())!=null)throw new AssertionError("Empty store was accepted");
        byte[] pem=Files.readAllBytes(Paths.get(args[0]));
        X509Certificate ca=Certificates.parse(pem);
        Files.write(dir.resolve("0f4ed297.2"),ca.getEncoded());
        Files.write(dir.resolve("47ec1af8.99"),Files.readAllBytes(Paths.get(args[1])));
        if(!AdcertController.personal(dir.toFile()).getName().equals("0f4ed297.2"))throw new AssertionError("Personal CA not selected");
        Files.write(dir.resolve("0f4ed297.10"),ca.getEncoded());
        if(!AdcertController.personal(dir.toFile()).getName().equals("0f4ed297.10"))throw new AssertionError("Numeric selection incorrect");
        Files.write(dir.resolve("0f4ed297.11"),Files.readAllBytes(Paths.get(args[1])));
        if(AdcertController.personal(dir.toFile())!=null)throw new AssertionError("Invalid latest candidate accepted");
        System.out.println("PASS switch CA presence, DER, intermediate exclusion and newest-candidate validation");
    }
}
