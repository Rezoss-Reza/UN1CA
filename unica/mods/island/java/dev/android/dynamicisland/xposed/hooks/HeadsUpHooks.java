package dev.android.dynamicisland.xposed.hooks;
import dev.android.dynamicisland.xposed.Refl;
public final class HeadsUpHooks {
    public static Object manager;
    public static String dismissStock(String key) {
        if(manager==null)return "no active banner";
        Refl.call(manager,"removeNotification",key,"native island",true);return "removed";
    }
}
