package android.os;
public class SystemProperties {
    public static boolean getBoolean(String name, boolean fallback) {
        String value = System.getenv(name);
        return value == null ? fallback : value.equals("true") || value.equals("1");
    }
}
