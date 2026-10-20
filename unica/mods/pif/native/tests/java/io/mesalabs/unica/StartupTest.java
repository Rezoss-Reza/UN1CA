package io.mesalabs.unica;
import android.os.Build;
public class StartupTest {
    public static void main(String[] args) {
        PifProcessHooks.initialize();
        boolean expectedActive=Boolean.parseBoolean(args[0]);
        boolean expectedBuild=Boolean.parseBoolean(args[1]);
        if (PifProcessHooks.isActive()!=expectedActive) throw new AssertionError("active guard");
        if (!Build.MODEL.equals(expectedBuild ? "Pixel 6a" : "SM-S918B")) throw new AssertionError("model");
        if (expectedBuild) {
            if (!Build.BRAND.equals("google") || !Build.DEVICE.equals("bluejay")) throw new AssertionError("identity");
            if (!Build.VERSION.RELEASE.equals("CANARY")) throw new AssertionError("version string");
            if (!Build.VERSION.SECURITY_PATCH.equals("2026-10-05")) throw new AssertionError("patch");
            if (Build.VERSION.DEVICE_INITIAL_SDK_INT!=32) throw new AssertionError("first api");
        }
        if (Build.VERSION.SDK_INT!=37) throw new AssertionError("SDK_INT must remain unchanged");
    }
}
