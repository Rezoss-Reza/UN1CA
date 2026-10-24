package dev.android.dynamicisland.xposed.hooks;
import java.util.*;
/** Native notifications remain managed by Samsung; no external LiveBridge dependency. */
public final class HideDuplicated {
    public static void rebuild() { dev.android.dynamicisland.island.NativeBridge.rebuildNotifications(); }
    public static Set<String> copyKeysOf(String key) { return Collections.emptySet(); }
    public static CharSequence originalSubText(CharSequence title,CharSequence source){return null;}
}
