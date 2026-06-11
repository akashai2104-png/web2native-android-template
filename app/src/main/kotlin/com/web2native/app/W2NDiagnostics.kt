package com.web2native.app

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import android.webkit.WebView
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipFile

/**
 * One-shot boot diagnostics. Runs once per cold start from W2NApplication.onCreate
 * on a background thread and POSTs a small JSON payload to the existing
 * `auth-debug-log` edge function with stage = "boot_diagnostics".
 *
 * Purely additive. Removing it (and the one call from W2NApplication.onCreate)
 * restores previous behavior with zero side effects.
 */
object W2NDiagnostics {

    private const val TAG = "W2N_DIAG"

    @Volatile private var sent = false

    fun report(app: android.app.Application, appProcessStartNs: Long) {
        if (sent) return
        sent = true

        val webViewProvider: String = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val pkg = WebView.getCurrentWebViewPackage()
                if (pkg != null) "${pkg.packageName}@${pkg.versionName}" else "unknown"
            } else "pre-O"
        } catch (_: Throwable) { "lookup_failed" }

        val webViewInitMs: Long = try {
            val t0 = System.nanoTime()
            val wv = WebView(app)
            wv.destroy()
            (System.nanoTime() - t0) / 1_000_000L
        } catch (_: Throwable) { -1L }

        val playServicesStatus: String = try {
            val cls = Class.forName("com.google.android.gms.common.GoogleApiAvailability")
            val getInstance = cls.getMethod("getInstance")
            val instance = getInstance.invoke(null)
            val isAvailable = cls.getMethod("isGooglePlayServicesAvailable", Context::class.java)
            val code = isAvailable.invoke(instance, app) as Int
            code.toString()
        } catch (_: Throwable) { "unavailable" }

        val installerPackage: String = try {
            val pm = app.packageManager
            if (Build.VERSION.SDK_INT >= 30) {
                pm.getInstallSourceInfo(app.packageName).installingPackageName ?: "sideload"
            } else {
                @Suppress("DEPRECATION")
                pm.getInstallerPackageName(app.packageName) ?: "sideload"
            }
        } catch (_: Throwable) { "unknown" }

        // Per-scheme detection from the installed APK file.
        val apkPath: String = try { app.applicationInfo.sourceDir ?: "" } catch (_: Throwable) { "" }
        val schemes = detectSigningSchemes(apkPath)

        val isLowRam: Boolean = try {
            val am = app.getSystemService(Context.ACTIVITY_SERVICE)
                    as? android.app.ActivityManager
            am?.isLowRamDevice == true
        } catch (_: Throwable) { false }

        val abi: String = try {
            if (Build.VERSION.SDK_INT >= 21) Build.SUPPORTED_ABIS.firstOrNull() ?: ""
            else @Suppress("DEPRECATION") Build.CPU_ABI
        } catch (_: Throwable) { "" }

        val timeSinceProcessStartMs = (System.nanoTime() - appProcessStartNs) / 1_000_000L

        val apiBase = try { BuildConfig.API_BASE_URL } catch (_: Throwable) { "" }
        val anonKey = try { BuildConfig.SUPABASE_ANON_KEY } catch (_: Throwable) { "" }
        val projectId = try { BuildConfig.PROJECT_ID } catch (_: Throwable) { "" }
        val pkg = try { app.packageName } catch (_: Throwable) { "" }
        val versionName = try { BuildConfig.VERSION_NAME ?: "" } catch (_: Throwable) { "" }
        val versionCode = try { BuildConfig.VERSION_CODE.toString() } catch (_: Throwable) { "" }
        val websiteUrl = try { BuildConfig.WEBSITE_URL } catch (_: Throwable) { "" }

        Log.d(TAG, "boot diag: webview=$webViewProvider initMs=$webViewInitMs " +
                "play=$playServicesStatus installer=$installerPackage " +
                "v1=${schemes.v1} v2=${schemes.v2} v3=${schemes.v3} v4=${schemes.v4} " +
                "lowRam=$isLowRam abi=$abi sinceStartMs=$timeSinceProcessStartMs")

        if (apiBase.isEmpty() || anonKey.isEmpty()) return

        val payload = """
            {
              "project_id": "${esc(projectId)}",
              "platform": "android",
              "source": "native-wrapper",
              "stage": "boot_diagnostics",
              "package_name": "${esc(pkg)}",
              "version_name": "${esc(versionName)}",
              "website_url": "${esc(websiteUrl)}",
              "details": {
                "device": "${esc("${Build.MANUFACTURER} ${Build.MODEL}")}",
                "sdk_int": "${Build.VERSION.SDK_INT}",
                "release": "${esc(Build.VERSION.RELEASE ?: "")}",
                "abi": "${esc(abi)}",
                "version_code": "${esc(versionCode)}",
                "low_ram": "$isLowRam",
                "webview_provider": "${esc(webViewProvider)}",
                "webview_init_ms": "$webViewInitMs",
                "play_services_status": "${esc(playServicesStatus)}",
                "installer_package": "${esc(installerPackage)}",
                "signing_v1": "${schemes.v1}",
                "signing_v2": "${schemes.v2}",
                "signing_v3": "${schemes.v3}",
                "signing_v4": "${schemes.v4}",
                "time_since_process_start_ms": "$timeSinceProcessStartMs"
              }
            }
        """.trimIndent()

        Thread {
            try {
                val url = URL("$apiBase/functions/v1/auth-debug-log")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.connectTimeout = 4000
                conn.readTimeout = 4000
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("Authorization", "Bearer $anonKey")
                conn.setRequestProperty("apikey", anonKey)
                conn.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
                conn.responseCode
                conn.disconnect()
            } catch (_: Throwable) { /* swallow — diagnostics must never affect the user */ }
        }.start()
    }

    private data class Schemes(val v1: Boolean, val v2: Boolean, val v3: Boolean, val v4: Boolean)

    /**
     * Detect APK Signature Schemes v1/v2/v3/v4 from the installed APK file.
     * - v1: presence of any META-INF SF entry (JAR signing).
     * - v2/v3: scan tail of APK for the scheme block magic IDs in the APK Signing Block.
     * - v4: presence of a sibling .apk.idsig file (v4 sig lives outside the APK).
     * All best-effort; failures degrade silently to false.
     */
    private fun detectSigningSchemes(apkPath: String): Schemes {
        if (apkPath.isEmpty()) return Schemes(false, false, false, false)

        val v1 = try {
            ZipFile(apkPath).use { zf ->
                val entries = zf.entries()
                var found = false
                while (entries.hasMoreElements()) {
                    val n = entries.nextElement().name
                    if (n.startsWith("META-INF/") && n.endsWith(".SF")) { found = true; break }
                }
                found
            }
        } catch (_: Throwable) { false }

        var v2 = false
        var v3 = false
        try {
            RandomAccessFile(apkPath, "r").use { raf ->
                val len = raf.length()
                // APK Signing Block sits just before the central directory; scan the last 4MB.
                val scanLen = minOf(len, 4L * 1024 * 1024).toInt()
                raf.seek(len - scanLen)
                val buf = ByteArray(scanLen)
                raf.readFully(buf)
                // v2 magic ID 0x7109871a (little-endian: 1a 87 09 71)
                // v3 magic ID 0xf05368c0 (little-endian: c0 68 53 f0)
                var i = 0
                val end = buf.size - 4
                while (i < end) {
                    val b0 = buf[i]
                    if (!v2 && b0 == 0x1a.toByte() && buf[i + 1] == 0x87.toByte()
                            && buf[i + 2] == 0x09.toByte() && buf[i + 3] == 0x71.toByte()) v2 = true
                    if (!v3 && b0 == 0xc0.toByte() && buf[i + 1] == 0x68.toByte()
                            && buf[i + 2] == 0x53.toByte() && buf[i + 3] == 0xf0.toByte()) v3 = true
                    if (v2 && v3) break
                    i++
                }
            }
        } catch (_: Throwable) { /* leave as-detected */ }

        val v4 = try { File("$apkPath.idsig").exists() } catch (_: Throwable) { false }

        return Schemes(v1, v2, v3, v4)
    }

    private fun esc(s: String?): String {
        if (s.isNullOrEmpty()) return ""
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "")
    }
}
