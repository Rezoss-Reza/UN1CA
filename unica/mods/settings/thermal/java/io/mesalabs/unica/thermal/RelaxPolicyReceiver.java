// SPDX-License-Identifier: GPL-3.0-or-later
package io.mesalabs.unica.thermal;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Process;
import android.os.ServiceManager;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;
import android.util.Log;
import java.io.File;
import java.io.FileDescriptor;
import java.nio.file.Files;

/** Runs inside SDHMS: no cross-app data access or new SELinux grants. */
public final class RelaxPolicyReceiver extends BroadcastReceiver {
    public static final String ACTION = "io.mesalabs.unica.thermal.POLICY";
    private static boolean restarting;
    private static volatile int parsedPolicy;

    /** Called by the existing policy loader after its success/fallback decision. */
    public static void policyParsed(String error) {
        parsedPolicy = error == null ? (n5.i0.b ? 2 : 1) : -1;
    }

    @Override public void onReceive(Context context, Intent intent) {
        if (!ACTION.equals(intent.getAction()) || !isOrderedBroadcast()
                || getSentFromUid() != 1000
                || !"com.android.settings".equals(getSentFromPackage())
                || Process.myUid() != 1000) return;
        Bundle reply = new Bundle();
        reply.putInt("protocol", 1);
        reply.putInt("pid", Process.myPid());
        try {
            File directory = context.getDataDir().getCanonicalFile();
            if (!directory.equals(new File("/data/user/0/com.sec.android.sdhms").getCanonicalFile())) {
                throw new SecurityException("Unexpected SDHMS data directory");
            }
            File marker = new File(directory, "ssrm.disable");
            boolean before = markerExists(marker);
            IBinder service = ServiceManager.checkService("sdhms");
            boolean ready = parsedPolicy > 0 && com.sec.android.sdhms.settings.k.g().l()
                    && service != null && service.pingBinder();
            boolean loaded = parsedPolicy == 2;
            if (intent.hasExtra("enabled")) {
                boolean enabled = intent.getBooleanExtra("enabled", false);
                if (restarting || (enabled && !ready)) {
                    throw new IllegalStateException("SDHMS is still starting");
                }
                if (enabled != before) {
                    if (enabled) {
                        FileDescriptor fd = Os.open(marker.getPath(), OsConstants.O_WRONLY
                                | OsConstants.O_CREAT | OsConstants.O_EXCL | OsConstants.O_NOFOLLOW, 0600);
                        try {
                            Os.fchown(fd, 1000, 1000);
                            Os.fchmod(fd, 0600);
                            Os.fsync(fd);
                        } finally { Os.close(fd); }
                    } else {
                        Files.delete(marker.toPath());
                    }
                    FileDescriptor fd = Os.open(directory.getPath(), OsConstants.O_RDONLY
                            | OsConstants.O_NOFOLLOW, 0);
                    try { Os.fsync(fd); } finally { Os.close(fd); }
                }
                if (enabled != loaded || !ready) {
                    restarting = true;
                    // The manifest declares SDHMS persistent; ActivityManager recreates
                    // this process and MainApplication initializes the policy again.
                    // Complete the ordered reply before ending only our own process.
                    new Handler(Looper.getMainLooper()).postDelayed(() -> {
                        Process.killProcess(Process.myPid());
                    }, 1000);
                }
            }
            reply.putBoolean("marker", markerExists(marker));
            reply.putBoolean("loaded", loaded);
            reply.putBoolean("ready", ready);
            reply.putBoolean("restarting", restarting);
            setResult(Activity.RESULT_OK, null, reply);
        } catch (Exception failure) {
            Log.e("UnicaRelaxSDHMS", "Policy request failed", failure);
            setResult(Activity.RESULT_CANCELED, null, reply);
        }
    }

    private static boolean markerExists(File marker) throws Exception {
        try {
            StructStat st = Os.lstat(marker.getPath());
            if (!OsConstants.S_ISREG(st.st_mode) || st.st_uid != 1000 || st.st_gid != 1000
                    || st.st_size != 0 || st.st_nlink != 1) {
                throw new SecurityException("Refusing an unexpected marker file");
            }
            return true;
        } catch (ErrnoException failure) {
            if (failure.errno == OsConstants.ENOENT) return false;
            throw failure;
        }
    }
}
