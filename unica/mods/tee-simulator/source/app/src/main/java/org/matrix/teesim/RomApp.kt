// Copyright (c) 2026 Rezoss
// SPDX-License-Identifier: GPL-3.0-or-later
package org.matrix.teesim

import android.app.ActivityThread
import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.os.FileObserver
import android.os.Handler
import android.os.Looper
import android.os.ServiceManager
import android.os.SystemClock
import android.os.SystemProperties
import java.io.File
import java.security.Security
import java.util.Base64
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.json.JSONArray
import org.json.JSONObject

/** ROM entry point. Settings retains XML import, validation, clear, and the original switch. */
object RomApp {
    private const val PROPERTY = "persist.sys.unica.keybox"
    private const val SETTING = "unica_keybox_data"
    private const val MAX_XML = 256 * 1024
    private val dirty = AtomicBoolean(true)
    private var observer: FileObserver? = null
    private var lastXml: String? = null
    private var lastWire: String? = null
    private var lastEnabled = false
    private var nextRead = 0L
    private var nextPackages = 0L
    private var retryAfter = 0L
    private var apps = emptyMap<Int, List<String>>()
    private var epoch = System.currentTimeMillis()
    @Volatile private var activeEpoch = -1L
    @Volatile private var acknowledged = false

    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size == 1)
        require(android.os.Process.myUid() == 0) { "ROM daemon requires uid 0" }
        ServiceManager.waitForService("package")
        Looper.prepareMainLooper()
        val thread = ActivityThread.systemMain()
        val context = thread.getSystemContext() as Any
        val app = Application()
        ContextWrapper::class.java.getDeclaredMethod("attachBaseContext", Context::class.java)
            .apply { isAccessible = true }.invoke(app, context)
        ActivityThread::class.java.getDeclaredField("mInitialApplication")
            .apply { isAccessible = true }.set(thread, app)
        android.security.keystore2.AndroidKeyStoreProvider.install()
        Security.removeProvider(BouncyCastleProvider.PROVIDER_NAME)
        Security.addProvider(BouncyCastleProvider())
        Packages.init(app)

        // Harvest before injection, and preserve its frozen boot key across daemon/ROM updates.
        // No resetprop, WebUI, key-database writes, or automatic existing-key deletion.
        val harvest = Harvester.run(app)
        Control.onRomAck = { msg ->
            acknowledged = msg.optLong("epoch", -1) == activeEpoch &&
                msg.optBoolean("ok") && msg.optInt("profilesApplied") == 1 &&
                msg.optInt("profilesFailed") == 0
            publishReady()
        }
        Control.onRomPong = { publishReady() }
        Control.onRomDisconnect = {
            acknowledged = false
            publishReady()
        }
        Control.start()
        Injector(File(args[0])).start()
        observer = object : FileObserver(File("/data/system/users/0"),
            CLOSE_WRITE or MOVED_TO or DELETE or CREATE) {
            override fun onEvent(event: Int, path: String?) {
                if (path == "settings_secure.xml") dirty.set(true)
            }
        }.also { it.startWatching() }

        val handler = Handler(Looper.getMainLooper())
        val tick = object : Runnable {
            override fun run() {
                try {
                    if (SystemClock.elapsedRealtime() >= retryAfter) update(harvest)
                } catch (error: Exception) {
                    // Do not log exceptions containing XML, command output, or private keys.
                    SystemLogger.warning("RomApp: refresh failed (${error.javaClass.simpleName}); disabling new interception")
                    push(harvest, false)
                    dirty.set(true)
                    retryAfter = SystemClock.elapsedRealtime() + 10_000
                } finally {
                    handler.postDelayed(this, 1000)
                }
            }
        }
        handler.post(tick)
        SystemLogger.info("RomApp: existing UN1CA Settings bridge started")
        Looper.loop()
    }

    private fun update(harvest: Harvester.Record) {
        val now = SystemClock.elapsedRealtime()
        val enabled = SystemProperties.get(PROPERTY, "false") in setOf("1", "true", "y", "yes", "on")
        // Property reads are in-process. The settings command is event driven, with a periodic
        // fallback for missed file events, atomic SettingsProvider writes, and imports while off.
        val refresh = dirty.getAndSet(false) || enabled != lastEnabled || now >= nextRead
        if (refresh) {
            val xml = readSetting()
            if (xml != null) lastXml = xml
            // A cleared setting disables routing immediately. Retain the last signer in MEMORY
            // only so existing simulator blobs can still be used during this daemon lifetime.
            // Nothing copies the imported XML out of Settings.Secure onto disk.
            if (xml == null) {
                push(harvest, false)
                lastEnabled = enabled
                nextRead = now + 30_000
                return
            }
            if (enabled && (apps.isEmpty() || now >= nextPackages)) {
                // One binder enumeration; no labels, icons, launchers, or per-app IPC polling.
                val pm = checkNotNull(Packages.packageManagerService())
                apps = pm.getInstalledApplications(0L, 0).list
                    .filter { it.uid in 10000..19999 }
                    .groupBy({ it.uid }, { it.packageName })
                nextPackages = now + 30_000
            }
            push(harvest, enabled)
            nextRead = now + 30_000
        }
        lastEnabled = enabled
    }

    private fun readSetting(): String? {
        // The command executes SettingsProvider's system-server shell interface. A bare
        // app_process is not an AMS app and cannot reliably acquire a ContentProvider itself.
        val process = ProcessBuilder("/system/bin/settings", "--user", "0", "get", "secure", SETTING)
            .redirectError(File("/dev/null")).start()
        val reader = java.util.concurrent.Executors.newSingleThreadExecutor()
        try {
            val future = reader.submit<String> {
                val out = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(4096)
                while (true) {
                    val count = process.inputStream.read(buffer)
                    if (count < 0) break
                    require(out.size() + count <= MAX_XML) { "setting exceeds backend limit" }
                    out.write(buffer, 0, count)
                }
                String(out.toByteArray(), Charsets.UTF_8).trim()
            }
            val xml = future.get(10, TimeUnit.SECONDS)
            check(process.waitFor(2, TimeUnit.SECONDS) && process.exitValue() == 0)
            return xml.takeUnless { it.isEmpty() || it == "null" }
        } finally {
            process.destroyForcibly()
            reader.shutdownNow()
        }
    }

    private fun push(h: Harvester.Record, active: Boolean) {
        val b64 = Base64.getEncoder()
        val boot = JSONObject().apply {
            put("verifiedBootKey", b64.encodeToString(h.effectiveBootKey()))
            put("verifiedBootHash", b64.encodeToString(h.effectiveBootHash()))
            h.effectiveModuleHash()?.let { put("moduleHash", b64.encodeToString(it)) }
            put("deviceLocked", true)
            put("verifiedBootState", 0)
            put("strongBoxAvailable", h.strongBoxAvailable)
            put("attestVersionTee", h.effectiveInt("attestationVersion", Harvester.effectiveAttestation(h).second))
            put("attestVersionStrongBox", h.strongBoxAttestationVersion)
        }
        val profiles = JSONArray()
        lastXml?.let { xml ->
            val packages = JSONArray()
            val users = JSONArray()
            val uids = JSONArray()
            val uidPackages = JSONArray()
            if (active) {
                // Match the original global control for owner-user application UIDs; system,
                // shell and keystore UIDs are excluded. Other Android users keep hardware keys.
                for ((uid, names) in apps.toSortedMap()) {
                    uids.put(uid)
                    uidPackages.put(names.firstOrNull() ?: "")
                    for (pkg in names.sorted()) { packages.put(pkg); users.put(0) }
                }
            }
            profiles.put(JSONObject().apply {
                put("id", "unica")
                put("keyboxB64", b64.encodeToString(xml.toByteArray(Charsets.UTF_8)))
                put("mode", "patch")
                put("securityLevel", Harvester.effectiveAttestation(h).first)
                h.osVersion?.let { put("osVersion", it) }
                // Keep ROM-reported levels; no calendar-based patch advancement.
                DeviceProps.propOsVersion()?.let { put("osVersion", it) }
                for ((field, kind, captured) in listOf(
                    Triple("osPatchLevel", "system", h.osPatchLevel),
                    Triple("vendorPatchLevel", "vendor", h.vendorPatchLevel),
                    Triple("bootPatchLevel", "boot", h.bootPatchLevel))) {
                    DeviceProps.resolvePatch("system_property", kind, captured)?.let { put(field, it) }
                }
                val ids = JSONObject()
                for (name in listOf("brand", "device", "product", "manufacturer", "model")) {
                    h.effective(name).takeIf { it.isNotBlank() }?.let { ids.put(name, it) }
                }
                put("deviceIds", ids)
                put("packages", packages)
                put("packageUsers", users)
                put("uids", uids)
                put("uidPackages", uidPackages)
            })
        }
        val msg = JSONObject().apply {
            put("type", "config")
            put("redactSalt", Redact.saltBase64())
            put("bootInfo", boot)
            put("profiles", profiles)
        }
        // Compare without epoch; unchanged Settings must not recreate TAs every poll.
        val wire = msg.toString()
        if (wire == lastWire) return
        val nextEpoch = ++epoch
        activeEpoch = if (active && lastXml != null) nextEpoch else -1L
        acknowledged = false
        publishReady()
        msg.put("epoch", nextEpoch)
        Control.push(msg.toString())
        lastWire = wire
        SystemLogger.info("RomApp: config queued, requested=${active && lastXml != null}")
    }

    private fun publishReady() {
        val enabled = SystemProperties.get(PROPERTY, "false") in setOf("1", "true", "y", "yes", "on")
        val value = if (enabled && acknowledged) SystemClock.elapsedRealtime().toString() else "0"
        SystemProperties.set("sys.unica.teesim.ready", value)
    }
}
