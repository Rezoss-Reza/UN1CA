/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.rezoss.moseygmshook;

import android.app.Application;
import android.app.AndroidAppHelper;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.ServiceInfo;
import android.util.Log;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** Exact-app experimental provider activation; no firmware/capability overrides. */
public final class MoseyGmsHook implements IXposedHookLoadPackage {
    private static final String TAG = "MoseyGmsHook";
    private static final String GMS = "com.google.android.gms";
    private static final String MOSEY = "com.google.android.mosey";
    private static final String SHARE = "com.samsung.android.app.sharelive";
    private final AtomicBoolean installed = new AtomicBoolean();

    @Override public void handleLoadPackage(final XC_LoadPackage.LoadPackageParam lp)
            throws Throwable {
        if (!GMS.equals(lp.packageName) && !MOSEY.equals(lp.packageName)
                && !SHARE.equals(lp.packageName)) return;
        if (GMS.equals(lp.packageName) && !GMS.equals(lp.processName)
                && !(GMS + ".persistent").equals(lp.processName)) return;

        // attach runs before Application.onCreate and before the registry caches flags.
        Application current = AndroidAppHelper.currentApplication();
        if (current != null && lp.packageName.equals(current.getPackageName())) {
            install(current, lp);
            return;
        }
        Method attach = Application.class.getDeclaredMethod("attach", Context.class);
        XposedBridge.hookMethod(attach, new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                Context context = (Context) p.args[0];
                if (context != null && lp.packageName.equals(context.getPackageName())) {
                    install(context, lp);
                }
            }
        });
    }

    private void install(Context context, XC_LoadPackage.LoadPackageParam lp) {
        if (!installed.compareAndSet(false, true)) return;
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(lp.packageName, 0);
            long expected = GMS.equals(lp.packageName) ? 264131035L
                    : MOSEY.equals(lp.packageName) ? 60715L : 1390231205L;
            if (info.getLongVersionCode() != expected) {
                log("SKIP unsupported version " + lp.packageName + " code="
                        + info.getLongVersionCode() + " expected=" + expected);
                return;
            }
            ClassLoader loader = lp.classLoader;
            log("v1.5 profile loaded package=" + lp.packageName + " process="
                    + lp.processName + " code=" + expected);
            if (GMS.equals(lp.packageName)) installGms(loader);
            else if (MOSEY.equals(lp.packageName)) installMosey(loader);
            else installShare(loader);
        } catch (Throwable t) {
            log("profile initialization failed: " + t.getClass().getName());
        }
    }

    private static void installGms(ClassLoader loader) {
        // kbfs implements kbfr. V() reads flag 45704091; the service uses it
        // to start the external provider registry. T() is a different flag.
        // Keep T, delayed-start policy, provider whitelist and metadata checks intact.
        try {
            Method gate = method(loader, "kbfs", "V", "boolean");
            final AtomicBoolean reported = new AtomicBoolean();
            XposedBridge.hookMethod(gate, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    if (p.hasThrowable()) return;
                    if (reported.compareAndSet(false, true)) {
                        log("registry gate kbfs.V original=" + p.getResult() + " effective=true");
                    }
                    p.setResult(Boolean.TRUE);
                }
            });
            log("installed registry gate kbfs.V()Z flag=45704091");
        } catch (Throwable t) {
            log("registry gate NOT installed: " + t.getClass().getName());
        }
        observe(loader, "com.google.android.gms.nearby.sharing.NearbySharingChimeraService",
                "onCreate", "void", "sharing service startup", false);
        observe(loader, "com.google.android.gms.nearby.sharing.NearbySharingChimeraService",
                "ac", "void", "registry lifecycle", false, "boolean");
        observe(loader, "epvw", "b", "boolean", "Mosey provider validation", true,
                "android.content.pm.ServiceInfo", "android.content.Context");
        observe(loader, "epug", "a", "android.content.ServiceConnection", "Mosey provider bind", true,
                "android.content.pm.ServiceInfo", "bryd", "android.content.Context", "kiaa",
                "android.content.Intent");
        observe(loader, "enve", "w", "void", "external provider discovery", false,
                "eird", "enud");
        observe(loader, "enve", "u", "void", "external provider advertising", false,
                "java.lang.String", "eitl", "enub");
    }

    private static void installMosey(ClassLoader loader) {
        // Observe original results; do not force aconfig, country, channels or capabilities.
        observe(loader, "dli", "j", "boolean", "Mosey app feature gate", false);
        observe(loader, "cad", "g", "int", "Mosey discovery", false, "aps", "apk");
        observe(loader, "cad", "f", "int", "Mosey advertising", false, "apj", "aqa");
        observe(loader, "bwz", "a", "bxc", "native Mosey service lookup", false);
        observe(loader, "bwy", "f", "void", "native Mosey start", false,
                "[I", "android.os.IBinder", "[B");
    }

    private static void installShare(ClassLoader loader) {
        // The captured v13.9.02.31 already reports protocolXSupported=true and
        // successfully calls setProvidersEnabled. Keep its support checks original.
        observe(loader, "wf.f", "f", "boolean", "Quick Share Mosey support", false);
        observe(loader, "xf.h", "run", "void", "Quick Share provider-enable request", false);
    }

    private static Method method(ClassLoader loader, String owner, String name,
            String returns, String... arguments) throws Exception {
        Class<?> type = Class.forName(owner, false, loader);
        Class<?>[] parameters = new Class<?>[arguments.length];
        for (int i = 0; i < arguments.length; i++) parameters[i] = type(loader, arguments[i]);
        Method result = type.getDeclaredMethod(name, parameters);
        if (!result.getReturnType().equals(type(loader, returns))
                || Modifier.isAbstract(result.getModifiers())
                || Modifier.isNative(result.getModifiers())) {
            throw new NoSuchMethodException(owner + "." + name + " prototype mismatch");
        }
        return result;
    }

    private static Class<?> type(ClassLoader loader, String name) throws ClassNotFoundException {
        if ("void".equals(name)) return void.class;
        if ("boolean".equals(name)) return boolean.class;
        if ("int".equals(name)) return int.class;
        return Class.forName(name, false, loader);
    }

    private static void observe(ClassLoader loader, String owner, String name, String returns,
            final String label, final boolean moseyOnly, String... arguments) {
        try {
            Method target = method(loader, owner, name, returns, arguments);
            final AtomicInteger entries = new AtomicInteger();
            final AtomicInteger exits = new AtomicInteger();
            XposedBridge.hookMethod(target, new XC_MethodHook() {
                private boolean relevant(MethodHookParam p) {
                    return !moseyOnly || (p.args.length > 0 && p.args[0] instanceof ServiceInfo
                            && MOSEY.equals(((ServiceInfo) p.args[0]).packageName));
                }
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    if (relevant(p) && entries.incrementAndGet() <= 8) log(label + " entered");
                }
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    if (!relevant(p) || exits.incrementAndGet() > 8) return;
                    String value;
                    if (p.hasThrowable()) value = "exception=" + p.getThrowable().getClass().getName();
                    else if (p.getResult() instanceof Boolean || p.getResult() instanceof Integer)
                        value = "result=" + p.getResult();
                    else if (((Method) p.method).getReturnType() == void.class) value = "completed";
                    else value = p.getResult() == null ? "result=null" : "result=present";
                    if (label.equals("registry lifecycle") || label.equals("sharing service startup")) {
                        try {
                            Field field = p.thisObject.getClass().getDeclaredField("bD");
                            field.setAccessible(true);
                            value += " registryStarted=" + field.getBoolean(p.thisObject);
                        } catch (ReflectiveOperationException ignored) {
                            value += " registryState=unavailable";
                        }
                    }
                    log(label + " " + value);
                }
            });
            log("installed observer " + owner + "." + name);
        } catch (Throwable t) {
            log("observer unavailable " + owner + "." + name + ": " + t.getClass().getName());
        }
    }

    private static void log(String message) {
        // No peer identifiers, channels, tokens or file contents are logged.
        Log.i(TAG, message);
    }
}
