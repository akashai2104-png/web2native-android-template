package com.web2native.app

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.os.Build
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebView
import java.net.HttpURLConnection
import java.net.URL

/**
 * Custom Application class that warms Chromium and prefetches the website
 * body the moment the app process starts — well before SplashActivity runs.
 *
 * Hardened so that NOTHING here can ever crash the process on constrained or
 * Android (Go-edition) devices, where WebView providers may be missing /
 * updating and Play Services classes may be partial.
 */
class W2NApplication : Application() {

    // Captured at the very first instruction of onCreate so we can report
    // accurate "time-since-process-start" measurements in both boot diagnostics
    // and fatal crash payloads.
    private val appProcessStartNs: Long = System.nanoTime()

    // Updated by the lifecycle callback we register below. Lets a fatal crash
    // payload tell us which Activity (and which lifecycle phase) was running
    // when the process died — invaluable for diagnosing crash-on-open reports.
    @Volatile private var lastLifecycleEvent: String = "process_start"

    override fun onCreate() {
        super.onCreate()

        // 1) Install a global crash net BEFORE doing anything else, so any later
        //    fatal exception (here, in MainActivity, in AdMob, in Firebase) is
        //    reported via telemetry instead of just silently killing the app.
        installGlobalCrashHandler()

        // 1b) Track Activity lifecycle so a crash report can pinpoint the phase.
        try {
            registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
                override fun onActivityCreated(a: android.app.Activity, b: android.os.Bundle?) {
                    lastLifecycleEvent = "${a.javaClass.simpleName}.created"
                }
                override fun onActivityStarted(a: android.app.Activity) {
                    lastLifecycleEvent = "${a.javaClass.simpleName}.started"
                }
                override fun onActivityResumed(a: android.app.Activity) {
                    lastLifecycleEvent = "${a.javaClass.simpleName}.resumed"
                }
                override fun onActivityPaused(a: android.app.Activity) {
                    lastLifecycleEvent = "${a.javaClass.simpleName}.paused"
                }
                override fun onActivityStopped(a: android.app.Activity) {
                    lastLifecycleEvent = "${a.javaClass.simpleName}.stopped"
                }
                override fun onActivitySaveInstanceState(a: android.app.Activity, b: android.os.Bundle) {}
                override fun onActivityDestroyed(a: android.app.Activity) {
                    lastLifecycleEvent = "${a.javaClass.simpleName}.destroyed"
                }
            })
        } catch (_: Throwable) { /* never block startup on instrumentation */ }

        // 2) Each step is independently guarded — one failure must not block the next.
        try { prewarmChromium() } catch (t: Throwable) {
            Log.w("W2N_APP", "prewarmChromium outer guard caught: ${t.javaClass.simpleName}: ${t.message}")
        }
        try { prefetchWebsiteBody() } catch (t: Throwable) {
            Log.w("W2N_APP", "prefetchWebsiteBody outer guard caught: ${t.javaClass.simpleName}: ${t.message}")
        }
        // 3) Boot diagnostics — fire-and-forget, never blocks startup.
        try { W2NDiagnostics.report(this, appProcessStartNs) } catch (_: Throwable) { /* swallow */ }
    }

    private fun isLowRamDevice(): Boolean {
        return try {
            val am = getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            am?.isLowRamDevice == true
        } catch (_: Throwable) { false }
    }

    private fun isWebViewAvailable(): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WebView.getCurrentWebViewPackage() != null
            } else true
        } catch (_: Throwable) { false }
    }

    private fun prewarmChromium() {
        // Skip on Go-edition / low-RAM devices — the cost (memory, GC) outweighs
        // the benefit, and a missing/updating WebView provider would crash here.
        if (isLowRamDevice()) {
            Log.d("W2N_APP", "Chromium prewarm skipped: low-RAM device")
            return
        }
        if (!isWebViewAvailable()) {
            Log.d("W2N_APP", "Chromium prewarm skipped: WebView provider unavailable")
            return
        }
        try {
            val wv = WebView(this)
            try {
                wv.settings.javaScriptEnabled = true
                wv.settings.domStorageEnabled = true
            } catch (_: Throwable) { /* ignore */ }
            try { CookieManager.getInstance().setAcceptCookie(true) } catch (_: Throwable) { /* ignore */ }
            try { wv.destroy() } catch (_: Throwable) { /* ignore */ }
            Log.d("W2N_APP", "Chromium prewarm OK")
        } catch (t: Throwable) {
            // MissingWebViewPackageException, AndroidRuntimeException, OOM, etc.
            Log.w("W2N_APP", "Chromium prewarm failed safely: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    private fun prefetchWebsiteBody() {
        if (isLowRamDevice()) {
            Log.d("W2N_APP", "Website prefetch skipped: low-RAM device")
            return
        }
        val websiteUrl = try { BuildConfig.WEBSITE_URL } catch (_: Throwable) { "" }
        if (websiteUrl.isEmpty()) return
        Thread {
            try {
                val url = URL(websiteUrl)
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "GET"
                conn.connectTimeout = 4000
                conn.readTimeout = 4000
                conn.instanceFollowRedirects = true
                conn.setRequestProperty("User-Agent", "WebToNative-Prefetch/1.0")
                val stream = conn.inputStream
                val buf = ByteArray(8192)
                var total = 0
                while (true) {
                    val n = stream.read(buf)
                    if (n < 0) break
                    total += n
                    if (total > 256 * 1024) break // cap at 256 KB to keep this cheap
                }
                stream.close()
                conn.disconnect()
                Log.d("W2N_APP", "Website body prefetch OK ($total bytes)")
            } catch (t: Throwable) {
                Log.d("W2N_APP", "Website body prefetch failed: ${t.message}")
            }
        }.start()
    }

    private fun installGlobalCrashHandler() {
        try {
            val previous = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
                try { reportFatalCrash(throwable) } catch (_: Throwable) { /* never throw from here */ }
                // Always delegate so Android still shows the standard "app stopped" UX
                // and the process exits in the usual way.
                if (previous != null) {
                    previous.uncaughtException(thread, throwable)
                } else {
                    android.os.Process.killProcess(android.os.Process.myPid())
                }
            }
        } catch (_: Throwable) { /* ignore */ }
    }

    private fun reportFatalCrash(throwable: Throwable) {
        // Best-effort, fire-and-forget: ship a tiny payload to the existing
        // auth-debug-log edge function so we get a stacktrace from the field.
        val apiBase = try { BuildConfig.API_BASE_URL } catch (_: Throwable) { "" }
        val anonKey = try { BuildConfig.SUPABASE_ANON_KEY } catch (_: Throwable) { "" }
        val projectId = try { BuildConfig.PROJECT_ID } catch (_: Throwable) { "" }
        if (apiBase.isEmpty() || anonKey.isEmpty()) return

        val pkg = try { packageName } catch (_: Throwable) { "" }
        val versionName = try { BuildConfig.VERSION_NAME } catch (_: Throwable) { "" } ?: ""
        val websiteUrl = try { BuildConfig.WEBSITE_URL } catch (_: Throwable) { "" }
        val exClass = throwable.javaClass.name
        val exMsg = (throwable.message ?: "").take(500)
        val frames = throwable.stackTrace.take(5).joinToString("\\n") { it.toString() }
        val deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}"
        val sdkInt = Build.VERSION.SDK_INT
        val timeSinceStartMs = ((System.nanoTime() - appProcessStartNs) / 1_000_000L).coerceAtLeast(0L)
        val lastEvent = lastLifecycleEvent
        val errorCode = classifyError(exClass, exMsg)

        val payload = """
            {
              "project_id": "${escape(projectId)}",
              "platform": "android",
              "source": "native-wrapper",
              "stage": "fatal_crash",
              "package_name": "${escape(pkg)}",
              "version_name": "${escape(versionName)}",
              "website_url": "${escape(websiteUrl)}",
              "details": {
                "device": "${escape(deviceModel)}",
                "sdk_int": "$sdkInt",
                "exception_class": "${escape(exClass)}",
                "exception_message": "${escape(exMsg)}",
                "stack_top": "${escape(frames)}",
                "error_code": "${escape(errorCode)}",
                "time_since_app_start_ms": "$timeSinceStartMs",
                "last_lifecycle_event": "${escape(lastEvent)}"
              }
            }
        """.trimIndent()

        // Synchronous on the dying thread — short timeout so we don't hang shutdown.
        try {
            val url = URL("$apiBase/functions/v1/auth-debug-log")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = 1500
            conn.readTimeout = 1500
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Authorization", "Bearer $anonKey")
            conn.setRequestProperty("apikey", anonKey)
            conn.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
            conn.responseCode // force send
            conn.disconnect()
        } catch (_: Throwable) { /* swallow — we're already crashing */ }
    }

    private fun escape(s: String?): String {
        if (s.isNullOrEmpty()) return ""
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "")
    }

    /**
     * Map common Android failure modes to a short, greppable code so we can
     * triage field crashes without parsing the full exception class+message.
     */
    private fun classifyError(exClass: String, exMsg: String): String {
        val haystack = "$exClass $exMsg"
        return when {
            haystack.contains("MissingWebViewPackageException") -> "WEBVIEW_PROVIDER_MISSING"
            haystack.contains("AndroidRuntimeException") && haystack.contains("WebView") -> "WEBVIEW_INIT_FAILED"
            haystack.contains("OutOfMemoryError") -> "OUT_OF_MEMORY"
            haystack.contains("UnsatisfiedLinkError") -> "NATIVE_LIB_MISSING"
            haystack.contains("ClassNotFoundException") -> "CLASS_NOT_FOUND"
            haystack.contains("NoClassDefFoundError") -> "CLASS_DEF_MISSING"
            haystack.contains("NoSuchMethodError") -> "METHOD_MISSING"
            haystack.contains("INSTALL_FAILED_") -> "INSTALL_FAILED"
            haystack.contains("SecurityException") -> "SECURITY_EXCEPTION"
            haystack.contains("NetworkOnMainThreadException") -> "NETWORK_ON_MAIN_THREAD"
            haystack.contains("DeadSystemException") -> "DEAD_SYSTEM"
            haystack.contains("TransactionTooLargeException") -> "TRANSACTION_TOO_LARGE"
            haystack.contains("RemoteServiceException") -> "REMOTE_SERVICE"
            haystack.contains("WindowManager\$BadTokenException") -> "BAD_WINDOW_TOKEN"
            else -> "UNCLASSIFIED"
        }
    }
}
