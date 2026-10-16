// Copyright (c) 2026 UN1CA contributors
// SPDX-License-Identifier: GPL-3.0-or-later
import com.android.apksig.ApkVerifier;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Arrays;

/** Verify ZIP signing blocks and require the selected ROM signing certificate. */
class VerifySignedPackage {
    public static void main(String[] args) throws Exception {
        X509Certificate expected;
        try (var stream = Files.newInputStream(Paths.get(args[0]))) {
            expected = (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(stream);
        }
        for (int i = 1; i < args.length; i++) {
            var result = new ApkVerifier.Builder(new File(args[i])).build().verify();
            if (!result.isVerified() || result.getSignerCertificates().size() != 1
                    || !Arrays.equals(expected.getEncoded(), result.getSignerCertificates().get(0).getEncoded())) {
                throw new IllegalStateException("Signature verification failed: " + args[i] + " " + result.getErrors());
            }
            System.out.println("Verified package signature: " + args[i]);
        }
    }
}
