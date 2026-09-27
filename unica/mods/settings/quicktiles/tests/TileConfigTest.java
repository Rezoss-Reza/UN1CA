/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.mesalabs.unica.quicktiles;

/** Pure JVM checks for DNS transitions and user-provided provider validation. */
public final class TileConfigTest {
    private static int checks;
    private static void equal(String expected, String actual) {
        checks++;
        if (!expected.equals(actual)) throw new AssertionError(expected + " != " + actual);
    }
    public static void main(String[] args) {
        String mode = "off";
        for (String expected : new String[]{"opportunistic", "hostname", "off", "opportunistic", "hostname", "off"}) {
            mode = TileConfig.nextDnsMode(mode); equal(expected, mode);
        }
        equal("dns.google", TileConfig.hostname(" DNS.Google "));
        equal("one.one.one.one", TileConfig.hostname("one.one.one.one"));
        equal("xn--bcher-kva.example", TileConfig.hostname("bücher.example"));
        for (String invalid : new String[]{"", "localhost", "1.1.1.1", "https://dns.google", "dns.google:853", "a..example", "-bad.example", "bad-.example", "bad_.example", "dns.google.", "a.example/path", "a@example.com"}) {
            boolean rejected = false;
            try { TileConfig.hostname(invalid); } catch (IllegalArgumentException expected) { rejected = true; }
            checks++;
            if (!rejected) throw new AssertionError("Accepted invalid provider: " + invalid);
        }
        System.out.println("Passed " + checks + " DNS checks");
    }
}
