package com.web2native.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.DisplayMetrics
import android.util.Log
import android.util.TypedValue
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.webkit.*
import android.view.animation.AlphaAnimation
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RelativeLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.browser.customtabs.CustomTabsClient
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.google.android.gms.ads.*
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback
import com.google.android.gms.ads.rewarded.RewardedAd
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback
import org.json.JSONObject
import java.io.File
import java.lang.reflect.Field
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var progressBar: ProgressBar
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private var loadingOverlay: LinearLayout? = null
    private var pageLoaded = false
    private var updateCheckDone = false
    private var wasInBackground = false
    private var backgroundSinceMs: Long = 0L
    private var skipNextResumeReload = false
    private val BACKGROUND_RELOAD_THRESHOLD_MS = 5 * 60 * 1000L // 5 minutes
    private var webViewManagedAuthInProgress = false
    private var lastCustomTabUrl: String? = null
    private var lastCustomTabReason: String? = null
    private var lastCustomTabOpenedAtMs: Long = 0L
    private var lastManagedAuthCompletionAtMs: Long = 0L
    private var brandingCheckHandler: android.os.Handler? = null
    private val BRANDING_RECHECK_INTERVAL_MS = 5 * 60 * 1000L // 5 min periodic recheck
    private val BRANDING_INITIAL_CHECK_DELAY_MS = 60 * 1000L // 60s first check

    // Trial overlay
    private lateinit var trialOverlayManager: TrialOverlayManager

    // File upload support
    private var fileUploadCallback: ValueCallback<Array<Uri>>? = null
    private var cameraPhotoPath: String? = null
    private lateinit var fileChooserLauncher: ActivityResultLauncher<Intent>
    private var pendingFileChooserParams: WebChromeClient.FileChooserParams? = null

    // Camera permission
    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        Log.d("W2N_CAMERA", "Camera permission ${if (granted) "GRANTED" else "DENIED"}")
        // Re-trigger file chooser flow with or without camera
        launchFileChooser(pendingFileChooserParams, cameraGranted = granted)
        pendingFileChooserParams = null
    }

    // Notification permission
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        Log.d("W2N_NOTIF", "Notification permission ${if (granted) "GRANTED" else "DENIED"}")
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (!BuildConfig.PUSH_ENABLED) return
        if (Build.VERSION.SDK_INT < 33) return // TIRAMISU
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            == PackageManager.PERMISSION_GRANTED) {
            Log.d("W2N_NOTIF", "Notification permission already granted")
            return
        }
        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun launchFileChooser(fileChooserParams: WebChromeClient.FileChooserParams?, cameraGranted: Boolean) {
        var takePictureIntent: Intent? = null
        if (cameraGranted) {
            takePictureIntent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
            try {
                val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                val photoFile = File.createTempFile("IMG_${timeStamp}_", ".jpg", cacheDir)
                cameraPhotoPath = "file:${photoFile.absolutePath}"
                val photoUri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", photoFile)
                takePictureIntent.putExtra(MediaStore.EXTRA_OUTPUT, photoUri)
            } catch (ex: Exception) {
                cameraPhotoPath = null
                takePictureIntent = null
            }
        } else {
            cameraPhotoPath = null
        }

        val contentSelectionIntent = Intent(Intent.ACTION_GET_CONTENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = fileChooserParams?.acceptTypes?.firstOrNull()?.takeIf { it.isNotEmpty() } ?: "*/*"
        }

        val chooserIntent = Intent(Intent.ACTION_CHOOSER).apply {
            putExtra(Intent.EXTRA_INTENT, contentSelectionIntent)
            putExtra(Intent.EXTRA_TITLE, "Choose file")
            if (takePictureIntent != null && cameraPhotoPath != null) {
                putExtra(Intent.EXTRA_INITIAL_INTENTS, arrayOf(takePictureIntent))
            }
        }

        fileChooserLauncher.launch(chooserIntent)
    }

    // AdMob
    private var interstitialAd: InterstitialAd? = null
    private var rewardedAd: RewardedAd? = null
    private var pageLoadCount = 0
    private var bannerDismissedAt: Long = 0
    private val BANNER_COOLDOWN_MS = 2 * 60 * 1000L // 2 min
    private var lastInterstitialTime: Long = 0
    private val INTERSTITIAL_COOLDOWN_MS = 60 * 1000L // 60 sec
    private val MIN_PAGE_LOADS_FOR_INTERSTITIAL = 5
    private val MIN_SESSION_TIME_MS = 60 * 1000L // 1 min
    private val appStartTime = System.currentTimeMillis()
    private val admobEnabled: Boolean
        get() = BuildConfig.ADMOB_APP_ID.isNotEmpty() && BuildConfig.ADMOB_APP_ID != "NONE"
    private val currentVersionCodeCompat: Int
        get() = getBuildConfigInt("VERSION_CODE_INT", BuildConfig.VERSION_CODE)
    private val rewardedAdUnitIdCompat: String
        get() = getBuildConfigString("ADMOB_REWARDED_ID")

    private fun getBuildConfigField(name: String): Field? {
        return try {
            BuildConfig::class.java.getField(name)
        } catch (_: Exception) {
            null
        }
    }

    private fun getBuildConfigInt(name: String, fallback: Int): Int {
        return try {
            getBuildConfigField(name)?.getInt(null) ?: fallback
        } catch (_: Exception) {
            fallback
        }
    }

    private fun getBuildConfigString(name: String, fallback: String = ""): String {
        return try {
            (getBuildConfigField(name)?.get(null) as? String) ?: fallback
        } catch (_: Exception) {
            fallback
        }
    }

    private fun getSystemNavigationBarColor(): Int {
        return try {
            val value = TypedValue()
            if (theme.resolveAttribute(android.R.attr.colorBackground, value, true)) {
                if (value.resourceId != 0) ContextCompat.getColor(this, value.resourceId) else value.data
            } else {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) Color.WHITE else Color.BLACK
            }
        } catch (_: Exception) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) Color.WHITE else Color.BLACK
        }
    }

    /**
     * Apply the OS's true system-default navigation bar:
     * - transparent nav bar (so the OS draws its own background / gesture pill)
     * - contrast enforcement on Q+ so icons/pill stay legible over app content
     * - light/dark icon appearance follows the device's current UI mode
     * Required for edge-to-edge with FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS.
     */
    private fun applySystemDefaultNavBar() {
        try {
            window.navigationBarColor = Color.TRANSPARENT
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                window.isNavigationBarContrastEnforced = true
            }
            val nightMode = resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK
            val isDark = nightMode == android.content.res.Configuration.UI_MODE_NIGHT_YES
            val controller = WindowCompat.getInsetsController(window, window.decorView)
            controller.isAppearanceLightNavigationBars = !isDark
        } catch (_: Exception) { }
    }


    private fun trackInstallOnce() {
        val prefs = getSharedPreferences("web2native_prefs", Context.MODE_PRIVATE)
        if (prefs.getBoolean("install_tracked", false)) return

        val apiBaseUrl = BuildConfig.API_BASE_URL
        val projectId = BuildConfig.PROJECT_ID
        if (apiBaseUrl.isEmpty() || projectId.isEmpty()) return

        Thread {
            try {
                val url = java.net.URL("$apiBaseUrl/functions/v1/track-app-install")
                val conn = url.openConnection() as java.net.HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("apikey", BuildConfig.SUPABASE_ANON_KEY)
                conn.doOutput = true
                // Best-effort device tags so we can see which OEMs / Android versions are silent.
                // Strings are JSON-escaped to keep the payload safe.
                fun esc(s: String?): String = (s ?: "").replace("\\", "\\\\").replace("\"", "\\\"")
                val manufacturer = esc(android.os.Build.MANUFACTURER)
                val model = esc(android.os.Build.MODEL)
                val osVersion = esc(android.os.Build.VERSION.RELEASE)
                val sdkInt = android.os.Build.VERSION.SDK_INT
                val body = """{"project_id":"$projectId","platform":"android","config_receipt":{"manufacturer":"$manufacturer","device_model":"$model","os_version":"$osVersion","sdk_int":$sdkInt}}"""
                conn.outputStream.use { it.write(body.toByteArray()) }
                val code = conn.responseCode
                if (code in 200..299) {
                    prefs.edit().putBoolean("install_tracked", true).apply()
                    Log.d("W2N_INSTALL", "Install tracked successfully")
                } else {
                    Log.w("W2N_INSTALL", "Track install failed: HTTP $code")
                }
                conn.disconnect()
            } catch (e: Exception) {
                Log.w("W2N_INSTALL", "Track install error", e)
            }
        }.start()
    }

    private fun checkForAppUpdate() {
        if (updateCheckDone) return
        updateCheckDone = true
        val apiBaseUrl = BuildConfig.API_BASE_URL
        val projectId = BuildConfig.PROJECT_ID
        if (apiBaseUrl.isEmpty() || projectId.isEmpty()) return

        Thread {
            try {
                val url = java.net.URL("$apiBaseUrl/functions/v1/check-app-version")
                val conn = url.openConnection() as java.net.HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("apikey", BuildConfig.SUPABASE_ANON_KEY)
                conn.connectTimeout = 3000
                conn.readTimeout = 3000
                conn.doOutput = true
                val body = """{"project_id":"$projectId","current_version_code":$currentVersionCodeCompat}"""
                conn.outputStream.use { it.write(body.toByteArray()) }
                val code = conn.responseCode
                if (code in 200..299) {
                    val response = conn.inputStream.bufferedReader().use { it.readText() }
                    val json = JSONObject(response)
                    val updateAvailable = json.optBoolean("update_available", false)
                    if (updateAvailable) {
                        val latestVersion = json.optString("latest_version", "")
                        val downloadUrl = json.optString("download_url", "")
                        val latestVersionCode = json.optInt("latest_version_code", 0)
                        val forceUpdate = json.optBoolean("force_update", false)

                        val prefs = getSharedPreferences("web2native_prefs", Context.MODE_PRIVATE)
                        val ignoredVersion = prefs.getInt("ignored_update_version_code", 0)
                        if (!forceUpdate && ignoredVersion >= latestVersionCode) {
                            Log.d("W2N_UPDATE", "Update v$latestVersionCode already dismissed")
                            return@Thread
                        }

                        runOnUiThread {
                            showUpdateBanner(latestVersion, downloadUrl, latestVersionCode, forceUpdate)
                        }
                    }
                }
                conn.disconnect()
            } catch (e: Exception) {
                Log.w("W2N_UPDATE", "Update check failed silently", e)
            }
        }.start()
    }

    private fun applyBrandingVisibility(brandingWatermark: TextView?, show: Boolean) {
        if (brandingWatermark == null) return
        if (show) {
            // Branding bar is always brand blue with white text, independent of
            // the user-selected theme/status-bar color. Free-tier only.
            brandingWatermark.setBackgroundColor(Color.parseColor("#1D4ED8"))
            brandingWatermark.setTextColor(Color.WHITE)
            brandingWatermark.visibility = View.VISIBLE
            brandingWatermark.setOnClickListener {
                openInChromeCustomTab("https://nativeappai.com", "branding")
            }
            val webViewParams = webView.layoutParams as? RelativeLayout.LayoutParams
            webViewParams?.addRule(RelativeLayout.ABOVE, R.id.brandingWatermark)
            webView.layoutParams = webViewParams
            val bannerParams = findViewById<FrameLayout>(R.id.bannerAdContainer)?.layoutParams as? RelativeLayout.LayoutParams
            bannerParams?.removeRule(RelativeLayout.ALIGN_PARENT_BOTTOM)
            bannerParams?.addRule(RelativeLayout.ABOVE, R.id.brandingWatermark)
            findViewById<FrameLayout>(R.id.bannerAdContainer)?.layoutParams = bannerParams
        } else {
            brandingWatermark.visibility = View.GONE
            val webViewParams = webView.layoutParams as? RelativeLayout.LayoutParams
            webViewParams?.removeRule(RelativeLayout.ABOVE)
            webViewParams?.addRule(RelativeLayout.ABOVE, R.id.bannerAdContainer)
            webView.layoutParams = webViewParams
            val bannerParams = findViewById<FrameLayout>(R.id.bannerAdContainer)?.layoutParams as? RelativeLayout.LayoutParams
            bannerParams?.addRule(RelativeLayout.ALIGN_PARENT_BOTTOM)
            findViewById<FrameLayout>(R.id.bannerAdContainer)?.layoutParams = bannerParams
        }
    }

    private fun checkBrandingStatus(brandingWatermark: TextView?, cachedValue: Boolean, retryCount: Int = 0) {
        val apiBaseUrl = BuildConfig.API_BASE_URL
        val projectId = BuildConfig.PROJECT_ID
        if (apiBaseUrl.isEmpty() || projectId.isEmpty()) return

        Thread {
            try {
                val url = java.net.URL("$apiBaseUrl/functions/v1/check-branding-status")
                val conn = url.openConnection() as java.net.HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("apikey", BuildConfig.SUPABASE_ANON_KEY)
                conn.connectTimeout = 8000
                conn.readTimeout = 8000
                conn.doOutput = true
                val body = """{"project_id":"$projectId"}"""
                conn.outputStream.use { it.write(body.toByteArray()) }
                val code = conn.responseCode
                if (code in 200..299) {
                    val response = conn.inputStream.bufferedReader().use { it.readText() }
                    val json = JSONObject(response)
                    val showBranding = json.optBoolean("show_branding", cachedValue)
                    Log.d("W2N_BRAND", "API returned show_branding=$showBranding (was cached=$cachedValue)")
                    val prefs = getSharedPreferences("web2native_prefs", Context.MODE_PRIVATE)
                    prefs.edit()
                        .putBoolean("branding_visible", showBranding)
                        .putBoolean("branding_api_confirmed", true)
                        .putLong("branding_last_checked", System.currentTimeMillis())
                        .apply()
                    // Always force-apply the API result to the UI
                    runOnUiThread {
                        val currentlyVisible = brandingWatermark?.visibility == View.VISIBLE
                        if (showBranding != currentlyVisible) {
                            animateBranding(brandingWatermark, showBranding)
                        } else {
                            // Even if visibility matches, ensure layout is correct
                            applyBrandingVisibility(brandingWatermark, showBranding)
                        }
                    }
                } else {
                    Log.w("W2N_BRAND", "Branding check failed: HTTP $code (attempt ${retryCount + 1})")
                    scheduleRetry(brandingWatermark, retryCount)
                }
                conn.disconnect()
            } catch (e: Exception) {
                Log.w("W2N_BRAND", "Branding check exception (attempt ${retryCount + 1})", e)
                scheduleRetry(brandingWatermark, retryCount)
            }
        }.start()
    }

    private fun scheduleRetry(brandingWatermark: TextView?, retryCount: Int) {
        val maxRetries = 4
        if (retryCount >= maxRetries) {
            Log.w("W2N_BRAND", "All $maxRetries branding retries exhausted — trusting cached state")
            return
        }
        val delayMs = (5000L * (retryCount + 1)).coerceAtMost(30000L) // 5s, 10s, 15s, 20s
        Log.d("W2N_BRAND", "Scheduling retry ${retryCount + 1}/$maxRetries in ${delayMs}ms")
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            val prefs = getSharedPreferences("web2native_prefs", Context.MODE_PRIVATE)
            val apiConfirmed = prefs.getBoolean("branding_api_confirmed", false)
            val cached = apiConfirmed && prefs.getBoolean("branding_visible", false)
            checkBrandingStatus(brandingWatermark, cached, retryCount + 1)
        }, delayMs)
    }

    private fun schedulePeriodicBrandingCheck(brandingWatermark: TextView?) {
        if (brandingCheckHandler != null) return // already scheduled
        brandingCheckHandler = android.os.Handler(android.os.Looper.getMainLooper())
        brandingCheckHandler?.postDelayed(object : Runnable {
            override fun run() {
                val prefs = getSharedPreferences("web2native_prefs", Context.MODE_PRIVATE)
                val apiConfirmed = prefs.getBoolean("branding_api_confirmed", false)
                val cached = apiConfirmed && prefs.getBoolean("branding_visible", false)
                Log.d("W2N_BRAND", "Periodic branding recheck (cached=$cached, apiConfirmed=$apiConfirmed)")
                checkBrandingStatus(brandingWatermark, cached)
                brandingCheckHandler?.postDelayed(this, BRANDING_RECHECK_INTERVAL_MS)
            }
        }, BRANDING_INITIAL_CHECK_DELAY_MS) // first check after 60s, then 5min intervals
    }

    private fun animateBranding(brandingWatermark: TextView?, show: Boolean) {
        if (brandingWatermark == null) return
        brandingWatermark.animate()
            .alpha(if (show) 1f else 0f)
            .setDuration(200)
            .withStartAction {
                if (show) {
                    applyBrandingVisibility(brandingWatermark, true)
                    brandingWatermark.alpha = 0f
                }
            }
            .withEndAction {
                if (!show) {
                    applyBrandingVisibility(brandingWatermark, false)
                }
            }
            .start()
    }

    // --- Runtime ad status check ---
    private var adsRuntimeEnabled: Boolean? = null

    private fun checkAdStatusRuntime() {
        if (!admobEnabled) return
        val apiBaseUrl = BuildConfig.API_BASE_URL
        val projectId = BuildConfig.PROJECT_ID
        if (apiBaseUrl.isEmpty() || projectId.isEmpty()) return

        Thread {
            try {
                val url = java.net.URL("$apiBaseUrl/functions/v1/check-ad-status")
                val conn = url.openConnection() as java.net.HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("apikey", BuildConfig.SUPABASE_ANON_KEY)
                conn.connectTimeout = 3000
                conn.readTimeout = 3000
                conn.doOutput = true
                val body = """{"project_id":"$projectId"}"""
                conn.outputStream.use { it.write(body.toByteArray()) }
                val code = conn.responseCode
                if (code in 200..299) {
                    val response = conn.inputStream.bufferedReader().use { it.readText() }
                    val json = JSONObject(response)
                    val enabled = json.optBoolean("ads_enabled", true)
                    val prefs = getSharedPreferences("web2native_prefs", Context.MODE_PRIVATE)
                    val wasPreviouslyEnabled = prefs.getBoolean("ads_runtime_enabled", true)
                    prefs.edit().putBoolean("ads_runtime_enabled", enabled).apply()
                    adsRuntimeEnabled = enabled
                    Log.d("W2N_ADMOB", "Runtime ad check: ads_enabled=$enabled (was=$wasPreviouslyEnabled)")
                    if (!enabled && wasPreviouslyEnabled) {
                        runOnUiThread {
                            findViewById<FrameLayout>(R.id.bannerAdContainer)?.visibility = View.GONE
                            interstitialAd = null
                            rewardedAd = null
                            Log.d("W2N_ADMOB", "Ads disabled at runtime — hiding banner, clearing interstitial/rewarded")
                        }
                    } else if (enabled && !wasPreviouslyEnabled) {
                        runOnUiThread {
                            if (BuildConfig.ADMOB_BANNER_ID.isNotEmpty()) {
                                initAdMob()
                                Log.d("W2N_ADMOB", "Ads re-enabled at runtime — reinitializing")
                            }
                        }
                    }
                }
                conn.disconnect()
            } catch (e: Exception) {
                Log.w("W2N_ADMOB", "Runtime ad check failed silently", e)
            }
        }.start()
    }

    private fun showUpdateBanner(latestVersion: String, downloadUrl: String, latestVersionCode: Int, forceUpdate: Boolean) {
        try {
            val themeColor = try { Color.parseColor(BuildConfig.THEME_COLOR) } catch (_: Exception) { Color.parseColor("#1D4ED8") }
            val rootLayout = swipeRefresh.getChildAt(0) as? RelativeLayout ?: return

            if (forceUpdate) {
                showForceUpdateOverlay(latestVersion, downloadUrl)
                return
            }

            val banner = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setBackgroundColor(themeColor)
                setPadding(32, 24, 16, 24)
                gravity = android.view.Gravity.CENTER_VERTICAL
                id = View.generateViewId()
            }

            val text = TextView(this).apply {
                this.text = "Update available (v$latestVersion)"
                setTextColor(Color.WHITE)
                textSize = 13f
                setTypeface(null, android.graphics.Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            banner.addView(text)

            val updateBtn = TextView(this).apply {
                this.text = "UPDATE"
                setTextColor(Color.WHITE)
                textSize = 12f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setBackgroundColor(Color.argb(60, 255, 255, 255))
                setPadding(24, 12, 24, 12)
                setOnClickListener {
                    try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(downloadUrl))) }
                    catch (e: Exception) { Log.w("W2N_UPDATE", "Failed to open download URL", e) }
                }
            }
            banner.addView(updateBtn)

            val laterBtn = TextView(this).apply {
                this.text = "LATER"
                setTextColor(Color.argb(200, 255, 255, 255))
                textSize = 12f
                setPadding(16, 12, 16, 12)
                setOnClickListener {
                    getSharedPreferences("web2native_prefs", Context.MODE_PRIVATE)
                        .edit().putInt("ignored_update_version_code", latestVersionCode).apply()
                    rootLayout.removeView(banner)
                }
            }
            banner.addView(laterBtn)

            val dismissBtn = TextView(this).apply {
                this.text = "✕"
                setTextColor(Color.argb(200, 255, 255, 255))
                textSize = 14f
                setPadding(16, 12, 16, 12)
                setOnClickListener {
                    getSharedPreferences("web2native_prefs", Context.MODE_PRIVATE)
                        .edit().putInt("ignored_update_version_code", latestVersionCode).apply()
                    rootLayout.removeView(banner)
                }
            }
            banner.addView(dismissBtn)

            val toolbar = findViewById<Toolbar>(R.id.toolbar)
            val bannerParams = RelativeLayout.LayoutParams(
                RelativeLayout.LayoutParams.MATCH_PARENT,
                RelativeLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                if (toolbar != null && toolbar.visibility == View.VISIBLE) {
                    addRule(RelativeLayout.BELOW, R.id.toolbar)
                } else {
                    addRule(RelativeLayout.ALIGN_PARENT_TOP)
                }
            }
            rootLayout.addView(banner, bannerParams)
            banner.bringToFront()
            Log.d("W2N_UPDATE", "Update banner shown for v$latestVersion")
        } catch (e: Exception) {
            Log.w("W2N_UPDATE", "Failed to show update banner", e)
        }
    }

    private fun showForceUpdateOverlay(latestVersion: String, downloadUrl: String) {
        try {
            val themeColor = try { Color.parseColor(BuildConfig.THEME_COLOR) } catch (_: Exception) { Color.parseColor("#1D4ED8") }
            val rootLayout = swipeRefresh.getChildAt(0) as? RelativeLayout ?: return

            val overlay = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(Color.argb(240, 0, 0, 0))
                gravity = android.view.Gravity.CENTER
                setPadding(64, 64, 64, 64)
                isClickable = true
                isFocusable = true
            }

            val title = TextView(this).apply {
                text = "Update Required"
                setTextColor(Color.WHITE)
                textSize = 22f
                setTypeface(null, android.graphics.Typeface.BOLD)
                gravity = android.view.Gravity.CENTER
            }
            overlay.addView(title)

            val desc = TextView(this).apply {
                text = "A new version (v$latestVersion) is available.\nPlease update to continue using the app."
                setTextColor(Color.argb(200, 255, 255, 255))
                textSize = 14f
                gravity = android.view.Gravity.CENTER
                setPadding(0, 24, 0, 48)
            }
            overlay.addView(desc)

            val updateBtn = TextView(this).apply {
                text = "UPDATE NOW"
                setTextColor(Color.WHITE)
                textSize = 16f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setBackgroundColor(themeColor)
                setPadding(48, 24, 48, 24)
                gravity = android.view.Gravity.CENTER
                setOnClickListener {
                    try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(downloadUrl))) }
                    catch (e: Exception) { Log.w("W2N_UPDATE", "Failed to open download URL", e) }
                }
            }
            overlay.addView(updateBtn)

            val overlayParams = RelativeLayout.LayoutParams(
                RelativeLayout.LayoutParams.MATCH_PARENT,
                RelativeLayout.LayoutParams.MATCH_PARENT
            )
            rootLayout.addView(overlay, overlayParams)
            overlay.bringToFront()
            Log.d("W2N_UPDATE", "Force update overlay shown for v$latestVersion")
        } catch (e: Exception) {
            Log.w("W2N_UPDATE", "Failed to show force update overlay", e)
        }
    }

    private fun maybeRegisterPushToken() {
        try {
            val firebaseServiceClass = Class.forName("com.web2native.app.WebToNativeFirebaseService")
            val ensureTokenRegistered = firebaseServiceClass.getMethod("ensureTokenRegistered")
            ensureTokenRegistered.invoke(null)
        } catch (_: ClassNotFoundException) {
            Log.d("W2N_FCM", "Push service unavailable; skipping token registration")
        } catch (_: NoSuchMethodException) {
            Log.w("W2N_FCM", "Push registration method unavailable")
        } catch (e: Exception) {
            Log.w("W2N_FCM", "Push token registration skipped", e)
        }
    }

    private fun lastCustomTabAgeMs(): String? {
        return if (lastCustomTabOpenedAtMs > 0L) {
            (System.currentTimeMillis() - lastCustomTabOpenedAtMs).toString()
        } else {
            null
        }
    }

    private fun clearLastCustomTabState() {
        lastCustomTabUrl = null
        lastCustomTabReason = null
        lastCustomTabOpenedAtMs = 0L
    }

    private fun isManagedAuthCooldownActive(): Boolean {
        return lastManagedAuthCompletionAtMs > 0L &&
            (System.currentTimeMillis() - lastManagedAuthCompletionAtMs) < 10_000L
    }

    private fun beginManagedWebViewAuthFlow(url: String) {
        lastManagedAuthCompletionAtMs = 0L
        if (!webViewManagedAuthInProgress) {
            // Clear auth cookies so Google account picker always shows
            CookieManager.getInstance().removeAllCookies(null)
            CookieManager.getInstance().flush()
            webView.evaluateJavascript(
                "try { sessionStorage.clear(); } catch(e) {}", null
            )
            Log.d("W2N_AUTH", "Cleared auth cookies before OAuth flow")
            Log.d("W2N_AUTH", "Locking OAuth flow to in-app WebView: $url")
            postAuthDiagnostic("oauth_webview_lock_started", mapOf(
                "url" to url,
                "lastCustomTabUrl" to lastCustomTabUrl,
                "lastCustomTabReason" to lastCustomTabReason,
            ))
        }
        webViewManagedAuthInProgress = true
    }

    private fun ensureGooglePromptParam(url: String): String {
        val uri = Uri.parse(url)
        if (uri.host?.contains("accounts.google.com") != true) return url
        if (uri.getQueryParameter("prompt") != null) return url
        return uri.buildUpon()
            .appendQueryParameter("prompt", "select_account")
            .build().toString()
    }

    private fun maybeCompleteManagedWebViewAuth(url: String?) {
        if (!webViewManagedAuthInProgress || url.isNullOrBlank()) return

        try {
            val uri = Uri.parse(url)
            val baseHost = Uri.parse(BuildConfig.WEBSITE_URL).host
            val sameDomain = isSameDomain(uri.host, baseHost)
            val isAuthFlow = isOAuthUrl(url) || isOAuthStarterUrl(url)

            if (sameDomain && !isAuthFlow) {
                Log.d("W2N_AUTH", "OAuth flow completed inside WebView: $url")
                lastManagedAuthCompletionAtMs = System.currentTimeMillis()
                postAuthDiagnostic("oauth_webview_lock_completed", mapOf(
                    "url" to url,
                    "host" to uri.host,
                    "cooldownActive" to isManagedAuthCooldownActive().toString(),
                ))
                webViewManagedAuthInProgress = false
            }
        } catch (e: Exception) {
            Log.w("W2N_AUTH", "Failed to inspect WebView OAuth completion", e)
        }
    }

    private fun postAuthDiagnostic(stage: String, details: Map<String, String?> = emptyMap()) {
        val apiBaseUrl = BuildConfig.API_BASE_URL
        val projectId = BuildConfig.PROJECT_ID
        val anonKey = BuildConfig.SUPABASE_ANON_KEY

        if (apiBaseUrl.isBlank() || projectId.isBlank() || anonKey.isBlank()) {
            Log.w("W2N_AUTH", "Skipping auth diagnostic upload for stage=$stage due to missing config")
            return
        }

        Thread {
            try {
                val conn = (java.net.URL("$apiBaseUrl/functions/v1/auth-debug-log").openConnection() as java.net.HttpURLConnection).apply {
                    requestMethod = "POST"
                    setRequestProperty("Content-Type", "application/json")
                    setRequestProperty("apikey", anonKey)
                    doOutput = true
                    connectTimeout = 8000
                    readTimeout = 8000
                }

                val payload = JSONObject().apply {
                    put("project_id", projectId)
                    put("platform", "android")
                    put("source", "native-wrapper")
                    put("stage", stage)
                    put("package_name", BuildConfig.APPLICATION_ID)
                    put("version_name", BuildConfig.VERSION_NAME)
                    put("website_url", BuildConfig.WEBSITE_URL)
                    put("details", JSONObject().apply {
                        details.forEach { (key, value) ->
                            put(key, value ?: JSONObject.NULL)
                        }
                    })
                }.toString()

                conn.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
                val code = conn.responseCode
                Log.d("W2N_AUTH", "Auth diagnostic uploaded: stage=$stage code=$code")
                conn.disconnect()
            } catch (e: Exception) {
                Log.w("W2N_AUTH", "Auth diagnostic upload failed: stage=$stage", e)
            }
        }.start()
    }

    private fun isNetworkAvailable(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun isSameDomain(urlHost: String?, baseHost: String?): Boolean {
        if (urlHost == null || baseHost == null) return false
        val u = urlHost.lowercase()
        val b = baseHost.lowercase()
        return u == b || u.endsWith(".$b") || b.endsWith(".$u")
    }

    private fun isWebsiteAppLink(uri: Uri?): Boolean {
        if (uri == null) return false
        val scheme = uri.scheme?.lowercase() ?: return false
        if (scheme != "https" && scheme != "http") return false

        val baseHost = try {
            Uri.parse(BuildConfig.WEBSITE_URL).host
        } catch (e: Exception) {
            Log.w("W2N_AUTH", "Failed to parse WEBSITE_URL while checking app link", e)
            null
        }

        return isSameDomain(uri.host, baseHost)
    }

    /**
     * Detect OAuth provider URLs that should be opened externally.
     */
    private fun isOAuthUrl(url: String): Boolean {
        val uri = Uri.parse(url)
        val host = uri.host?.lowercase() ?: ""

        val oauthHosts = listOf(
            "accounts.google.com",
            "oauth2.googleapis.com",
            "appleid.apple.com",
            "www.facebook.com",
            "facebook.com",
            "github.com",
            "login.microsoftonline.com",
            "discord.com",
            "login.live.com",
            "auth0.com",
            "clerk.dev",
            "clerk.accounts.dev",
            "okta.com",
            "cognito-idp.amazonaws.com",
            "amazoncognito.com",
            "supabase.co",
            "supabase.com",
            "lovable.dev",
            "lovable.app"
        )

        return oauthHosts.any { host == it || host.endsWith(".$it") }
    }

    /**
     * Detect same-domain starter URLs that kick off an OAuth redirect chain.
     */
    private fun isOAuthStarterUrl(url: String): Boolean {
        val uri = Uri.parse(url)
        val path = uri.path?.lowercase() ?: ""

        val explicitOAuthPath = listOf(
            "/auth/v1/authorize",
            "/oauth/authorize",
            "/authorize",
            "/api/auth/signin",
            "/connect/authorize",
            "/~oauth/initiate"
        ).any { path.contains(it) }

        val hasOAuthQuery = listOf(
            "provider",
            "redirect_uri",
            "redirect_to",
            "client_id",
            "response_type",
            "scope",
            "code_challenge",
            "state"
        ).any { uri.getQueryParameter(it) != null }

        val genericAuthPath = path.contains("/auth") || path.contains("/oauth") || path.contains("/authorize")

        return explicitOAuthPath || (genericAuthPath && hasOAuthQuery)
    }

    private fun isSameDomainOAuthStarterUrl(url: String): Boolean {
        return try {
            val uri = Uri.parse(url)
            val baseHost = Uri.parse(BuildConfig.WEBSITE_URL).host
            isOAuthStarterUrl(url) && isSameDomain(uri.host, baseHost)
        } catch (e: Exception) {
            Log.w("W2N_AUTH", "Failed to classify same-domain OAuth starter URL", e)
            false
        }
    }

    private fun isManagedOAuthBrokerUrl(url: String): Boolean {
        return try {
            val uri = Uri.parse(url)
            val host = uri.host?.lowercase() ?: return false
            val path = uri.path?.lowercase() ?: ""
            host == "oauth.lovable.app" && path.contains("/initiate")
        } catch (e: Exception) {
            Log.w("W2N_AUTH", "Failed to classify managed OAuth broker URL", e)
            false
        }
    }

    /**
     * Build the oauth-bounce HTTPS URL that will redirect to web2native://auth.
     */
    private fun buildOAuthBounceUrl(returnTo: String): String {
        val apiBaseUrl = BuildConfig.API_BASE_URL.trimEnd('/')
        if (apiBaseUrl.isBlank()) {
            Log.w("W2N_AUTH", "API_BASE_URL is blank — cannot build oauth-bounce URL")
            return returnTo
        }
        val bounceEndpoint = "$apiBaseUrl/functions/v1/oauth-bounce"
        return Uri.parse(bounceEndpoint)
            .buildUpon()
            .appendQueryParameter("returnTo", returnTo)
            .build()
            .toString()
    }

    private fun getOAuthStarterRedirectTarget(url: String): String? {
        return try {
            val uri = Uri.parse(url)
            uri.getQueryParameter("redirect_to")
                ?: uri.getQueryParameter("redirect_uri")
        } catch (e: Exception) {
            Log.w("W2N_AUTH", "Failed to inspect OAuth starter redirect target", e)
            null
        }
    }

    /**
     * Wrapper-owned OAuth can safely use CCT only when the client site redirects
     * back to a reclaimable callback route that our app knows how to resume from.
     *
     * For arbitrary wrapped sites that land back on /dashboard or /login, CCT gets
     * stuck because Android cannot reliably reclaim the tab without a verified app
     * link or a wrapper-owned callback page. In those cases we keep the full OAuth
     * chain inside the managed WebView instead.
     */
    private fun shouldHandleOAuthStarterInWebView(url: String): Boolean {
        val redirectTarget = getOAuthStarterRedirectTarget(url)
        if (redirectTarget.isNullOrBlank()) {
            Log.w("W2N_AUTH", "OAuth starter missing redirect target — defaulting to managed WebView auth")
            return true
        }

        return try {
            val redirectUri = Uri.parse(redirectTarget)
            val baseHost = Uri.parse(BuildConfig.WEBSITE_URL).host
            val redirectPath = redirectUri.path?.lowercase() ?: "/"
            val sameDomainRedirect = isSameDomain(redirectUri.host, baseHost)
            val usesWrapperCallback = sameDomainRedirect && redirectPath == "/auth-return"

            !usesWrapperCallback
        } catch (e: Exception) {
            Log.w("W2N_AUTH", "Failed to inspect OAuth starter redirect target — defaulting to managed WebView auth", e)
            true
        }
    }

    /**
     * Rewrite the same-domain OAuth starter redirect_uri so the managed flow
     * returns through oauth-bounce, which closes CCT and hands control back to
     * the app while preserving the original callback target in returnTo.
     */
    private fun rewriteOAuthStarterRedirectUriForAuthTab(url: String): String {
        return try {
            val originalUri = Uri.parse(url)
            val originalRedirectUri = originalUri.getQueryParameter("redirect_uri")

            if (originalRedirectUri.isNullOrBlank()) {
                Log.w("W2N_AUTH", "OAuth starter URL missing redirect_uri — leaving as-is")
                return url
            }

            val bounceUrl = buildOAuthBounceUrl(originalRedirectUri)
            val builder = originalUri.buildUpon().clearQuery()

            for (name in originalUri.queryParameterNames) {
                if (name == "redirect_uri") {
                    builder.appendQueryParameter("redirect_uri", bounceUrl)
                    continue
                }
                for (value in originalUri.getQueryParameters(name)) {
                    builder.appendQueryParameter(name, value)
                }
            }

            val rewrittenUrl = builder.build().toString()
            Log.d("W2N_AUTH", "Rewrote OAuth starter redirect_uri for Auth Tab")
            Log.d("W2N_AUTH", "│ original=$url")
            Log.d("W2N_AUTH", "│ originalRedirectUri=$originalRedirectUri")
            Log.d("W2N_AUTH", "│ bounceUrl=$bounceUrl")
            Log.d("W2N_AUTH", "└─ rewritten=$rewrittenUrl")
            rewrittenUrl
        } catch (e: Exception) {
            Log.w("W2N_AUTH", "Failed to rewrite OAuth starter redirect_uri for Auth Tab", e)
            url
        }
    }


    /**
     * Launch OAuth flow in Chrome Custom Tab.
     * No redirect_uri rewriting — the original URL is opened as-is
     * so the Lovable Cloud proxy accepts the redirect_uri match.
     */
    private fun launchAuthTab(url: String) {
        Log.d("W2N_AUTH", "┌─ launchAuthTab (CCT) ──────────────────")
        Log.d("W2N_AUTH", "│ url=$url")

        val finalUrl = url  // No rewriting — use original URL as-is

        Log.d("W2N_AUTH", "│ finalUrl=$finalUrl")

        postAuthDiagnostic("auth_tab_launch", mapOf(
            "sourceUrl" to url,
            "finalUrl" to finalUrl,
            "isSameDomainStarter" to isSameDomainOAuthStarterUrl(url).toString(),
            "method" to "cct",
        ))

        openInChromeCustomTab(finalUrl, "oauth")
        Log.d("W2N_AUTH", "└─ OAuth CCT launched")
    }

    /**
     * Find a browser package that supports Chrome Custom Tabs.
     */
    private fun getCustomTabsPackage(): String? {
        return try {
            val preferredPackages = listOf(
                "com.android.chrome",
                "com.chrome.beta",
                "com.chrome.dev",
                "com.google.android.apps.chrome",
                "com.microsoft.emmx",
                "org.mozilla.firefox"
            )
            androidx.browser.customtabs.CustomTabsClient.getPackageName(this, preferredPackages)
                ?: androidx.browser.customtabs.CustomTabsClient.getPackageName(this, null)
        } catch (e: Exception) {
            Log.w("W2N_AUTH", "Failed to query Custom Tabs packages", e)
            null
        }
    }

    /**
     * Opens a URL in Chrome Custom Tab (used for non-OAuth external links).
     */
    private fun openInChromeCustomTab(url: String, reason: String = "external") {
        val ctPackage = getCustomTabsPackage()

        if (reason == "oauth") {
            lastCustomTabUrl = url
            lastCustomTabReason = reason
            lastCustomTabOpenedAtMs = System.currentTimeMillis()
            postAuthDiagnostic("oauth_cct_launch_attempt", mapOf(
                "url" to url,
                "reason" to reason,
                "customTabsPackage" to ctPackage,
            ))
        }

        if (ctPackage == null) {
            Log.w("W2N_AUTH", "No Custom Tabs provider found — loading URL in WebView")
            try {
                webView.post { webView.loadUrl(url) }
            } catch (e: Exception) {
                Log.e("W2N_AUTH", "WebView fallback failed", e)
            }
            return
        }

        try {
            val customTabsIntent = CustomTabsIntent.Builder()
                .setShowTitle(true)
                .setUrlBarHidingEnabled(true)
                .build()

            customTabsIntent.intent.setPackage(ctPackage)
            Log.d("W2N_AUTH", "Opening Chrome Custom Tab via package: $ctPackage for url=$url")
            customTabsIntent.launchUrl(this, Uri.parse(url))
        } catch (e: Exception) {
            Log.e("W2N_AUTH", "Chrome Custom Tab launch failed", e)
            try {
                webView.post { webView.loadUrl(url) }
            } catch (e2: Exception) {
                Log.e("W2N_AUTH", "All navigation methods failed", e2)
            }
        }
    }

    /**
     * Navigation override:
     * - Same-domain pages and same-domain OAuth chains → keep in WebView
     * - External OAuth URLs → Chrome Custom Tab
     * - External URLs → Chrome Custom Tab
     */
    // ─────────────── Cross-device compatibility helpers ───────────────

    private fun isWebViewProviderAvailable(): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WebView.getCurrentWebViewPackage() != null
            } else true
        } catch (_: Throwable) { false }
    }

    private fun showWebViewMissingScreen() {
        try {
            val container = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = android.view.Gravity.CENTER
                setPadding(48, 48, 48, 48)
                setBackgroundColor(Color.WHITE)
            }
            val title = TextView(this).apply {
                text = "Update required"
                textSize = 22f
                setTextColor(Color.parseColor("#0F172A"))
                setPadding(0, 0, 0, 24)
                gravity = android.view.Gravity.CENTER
            }
            val body = TextView(this).apply {
                text = "This app needs Android System WebView to run. Please install or update it from the Play Store, then reopen the app."
                textSize = 15f
                setTextColor(Color.parseColor("#475569"))
                gravity = android.view.Gravity.CENTER
                setPadding(0, 0, 0, 32)
            }
            val btn = TextView(this).apply {
                text = "Open Play Store"
                textSize = 15f
                setTextColor(Color.WHITE)
                setBackgroundColor(Color.parseColor("#1D4ED8"))
                setPadding(48, 24, 48, 24)
                setOnClickListener {
                    try {
                        startActivity(Intent(
                            Intent.ACTION_VIEW,
                            Uri.parse("market://details?id=com.google.android.webview")
                        ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
                    } catch (_: Throwable) {
                        try {
                            startActivity(Intent(
                                Intent.ACTION_VIEW,
                                Uri.parse("https://play.google.com/store/apps/details?id=com.google.android.webview")
                            ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
                        } catch (_: Throwable) { }
                    }
                }
            }
            container.addView(title)
            container.addView(body)
            container.addView(btn)
            setContentView(container)
        } catch (_: Throwable) {
            finish()
        }
    }

    /**
     * Handle non-http(s) schemes that the WebView can't load and CCT can't open.
     * Returns true when the URL was consumed (and the caller should NOT load it).
     */
    private fun handleSpecialScheme(url: String): Boolean {
        if (url.isEmpty()) return false
        val lower = url.lowercase()
        if (lower.startsWith("intent://") || lower.startsWith("intent:")) {
            return try {
                val intent = Intent.parseUri(url, Intent.URI_INTENT_SCHEME)
                val fallback = intent.getStringExtra("browser_fallback_url")
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (intent.resolveActivity(packageManager) != null) {
                    startActivity(intent)
                } else if (!fallback.isNullOrEmpty()) {
                    if (::webView.isInitialized) webView.loadUrl(fallback)
                }
                true
            } catch (t: Throwable) {
                Log.w("W2N_SCHEME", "intent:// parse failed: ${t.message}")
                true
            }
        }
        val isSpecial = lower.startsWith("tel:") || lower.startsWith("sms:") ||
            lower.startsWith("mailto:") || lower.startsWith("geo:") ||
            lower.startsWith("market:") || lower.startsWith("whatsapp:") ||
            lower.startsWith("upi:") || lower.startsWith("tg:") ||
            lower.startsWith("fb-messenger:") || lower.startsWith("zoomus:") ||
            lower.startsWith("skype:") || lower.startsWith("paytmmp:") ||
            lower.startsWith("phonepe:") || lower.startsWith("gpay:")
        if (!isSpecial) return false
        return try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (intent.resolveActivity(packageManager) != null) {
                startActivity(intent)
            } else {
                Toast.makeText(this, "No app available to open this link", Toast.LENGTH_SHORT).show()
            }
            true
        } catch (t: Throwable) {
            Log.w("W2N_SCHEME", "Failed to launch scheme: ${t.message}")
            true
        }
    }

    private fun shouldOverrideNavigation(url: String): Boolean {

        // ── Special schemes (tel:, mailto:, sms:, intent:, whatsapp:, upi:, etc.) ──
        // These can never be loaded by a WebView or by Chrome Custom Tabs, so handle
        // them explicitly with ACTION_VIEW. Without this, taps on phone-number /
        // email / WhatsApp links silently do nothing on every device.
        if (handleSpecialScheme(url)) return true

        val uri = Uri.parse(url)
        val host = uri.host?.lowercase() ?: ""
        val baseHost = Uri.parse(BuildConfig.WEBSITE_URL).host
        val sameDomain = isSameDomain(host, baseHost)
        val isOAuth = isOAuthUrl(url)
        val isStarter = isOAuthStarterUrl(url)
        val isBroker = isManagedOAuthBrokerUrl(url)
        val managedAuthCooldownActive = isManagedAuthCooldownActive()

        Log.d("W2N_NAV", "┌─ shouldOverrideNavigation ─────────────────")
        Log.d("W2N_NAV", "│ url=$url")
        Log.d("W2N_NAV", "│ host=$host baseHost=$baseHost sameDomain=$sameDomain")
        Log.d("W2N_NAV", "│ isOAuthUrl=$isOAuth isOAuthStarterUrl=$isStarter isBroker=$isBroker")
        Log.d("W2N_NAV", "│ managedAuthInProgress=$webViewManagedAuthInProgress")
        Log.d("W2N_NAV", "│ managedAuthCooldownActive=$managedAuthCooldownActive")

        fun logDecision(decision: String, action: String) {
            Log.d("W2N_NAV", "│ DECISION=$decision ACTION=$action")
            Log.d("W2N_NAV", "└─────────────────────────────────────────")
            postAuthDiagnostic("nav_override_decision", mapOf(
                "url" to url,
                "host" to host,
                "sameDomain" to sameDomain.toString(),
                "isOAuth" to isOAuth.toString(),
                "isStarter" to isStarter.toString(),
                "isBroker" to isBroker.toString(),
                "managedAuthInProgress" to webViewManagedAuthInProgress.toString(),
                "managedAuthCooldownActive" to managedAuthCooldownActive.toString(),
                "decision" to decision,
                "action" to action,
            ))
        }

        // ── Managed WebView auth in progress ──────────────────────────
        if (webViewManagedAuthInProgress) {
            logDecision("KEEP_MANAGED_AUTH_WEBVIEW", "webview")
            val rewrittenUrl = ensureGooglePromptParam(url)
            if (rewrittenUrl != url) {
                webView.loadUrl(rewrittenUrl)
                return true
            }
            return false
        }

        if (managedAuthCooldownActive && (isOAuth || isStarter || isBroker)) {
            logDecision("KEEP_RECENT_AUTH_WEBVIEW", "webview")
            return false
        }

        // ── Same-domain OAuth starter ────────────────────────────────
        // Always keep OAuth in WebView so Google login form renders
        // directly instead of opening in CCT (which users don't expect).
        if (sameDomain && isStarter) {
            logDecision("BEGIN_MANAGED_WEBVIEW_AUTH", "webview")
            beginManagedWebViewAuthFlow(url)
            return false
        }

        // Same domain non-OAuth → keep in WebView
        if (sameDomain && !isStarter) {
            logDecision("KEEP_SAME_DOMAIN", "webview")
            return false
        }

        // External OAuth URLs → keep in WebView when managed auth is active
        // Otherwise, route through WebView to show Google sign-in form directly
        if (isOAuth || isStarter) {
            if (webViewManagedAuthInProgress || managedAuthCooldownActive) {
                logDecision("KEEP_OAUTH_IN_WEBVIEW", "webview")
                return false
            }
            // Default: keep OAuth in WebView for better UX
            logDecision("BEGIN_MANAGED_WEBVIEW_AUTH_EXTERNAL", "webview")
            beginManagedWebViewAuthFlow(url)
            return false
        }

        // External non-OAuth → Chrome Custom Tab
        logDecision("OPEN_EXTERNAL_CCT", "openInChromeCustomTab")
        openInChromeCustomTab(url)
        return true
    }

    // TEMPLATE_MARKER:MainActivity_oauth_v30

    private fun mergeCallbackParamsIntoUrl(baseUrl: String, sourceUri: Uri): String {
        return try {
            val callbackParams = linkedMapOf<String, List<String>>()

            for (name in sourceUri.queryParameterNames) {
                if (name == "returnTo") continue
                when (name) {
                    "code",
                    "access_token",
                    "refresh_token",
                    "provider_token",
                    "provider_refresh_token",
                    "token_hash",
                    "type",
                    "error",
                    "error_code",
                    "error_description",
                    "state" -> callbackParams[name] = sourceUri.getQueryParameters(name)
                }
            }

            if (callbackParams.isEmpty()) return baseUrl

            val baseUri = Uri.parse(baseUrl)
            val builder = baseUri.buildUpon().clearQuery()

            for (name in baseUri.queryParameterNames) {
                if (callbackParams.containsKey(name)) continue
                for (value in baseUri.getQueryParameters(name)) {
                    builder.appendQueryParameter(name, value)
                }
            }

            for ((name, values) in callbackParams) {
                for (value in values) {
                    builder.appendQueryParameter(name, value)
                }
            }

            builder.build().toString()
        } catch (e: Exception) {
            Log.w("W2N_AUTH", "Failed to merge OAuth callback params into return URL", e)
            baseUrl
        }
    }

    /**
     * Replays the OAuth callback URL inside the native WebView.
     */
    private fun handleAuthReturnIntent(intent: Intent?): Boolean {
        val uri = intent?.data
        Log.d("W2N_AUTH", "┌─ handleAuthReturnIntent ──────────────────")
        Log.d("W2N_AUTH", "│ uri=$uri")
        Log.d("W2N_AUTH", "│ scheme=${uri?.scheme} host=${uri?.host}")
        Log.d("W2N_AUTH", "│ fullIntent=${intent?.toUri(Intent.URI_INTENT_SCHEME)}")

        if (uri == null) {
            Log.d("W2N_AUTH", "└─ NO deep link data — ignoring")
            return false
        }

        postAuthDiagnostic("auth_return_intent_received", mapOf(
            "uri" to uri.toString(),
            "scheme" to uri.scheme,
            "host" to uri.host,
            "lastCustomTabUrl" to lastCustomTabUrl,
            "lastCustomTabReason" to lastCustomTabReason,
            "lastCustomTabAgeMs" to lastCustomTabAgeMs(),
        ))

        val isCustomScheme = uri.scheme == "web2native"
        val isWebsiteLink = isWebsiteAppLink(uri)

        if (!isCustomScheme && !isWebsiteLink) {
            Log.d("W2N_AUTH", "└─ Unsupported auth return URI — ignoring")
            postAuthDiagnostic("auth_return_intent_ignored", mapOf(
                "uri" to uri.toString(),
                "scheme" to uri.scheme,
                "host" to uri.host,
            ))
            return false
        }

        val returnTo = if (isCustomScheme) uri.getQueryParameter("returnTo") else null
        val baseTargetUrl = when {
            !returnTo.isNullOrBlank() -> returnTo
            isWebsiteLink -> uri.toString()
            else -> BuildConfig.WEBSITE_URL
        }
        val targetUrl = if (isCustomScheme) mergeCallbackParamsIntoUrl(baseTargetUrl, uri) else baseTargetUrl

        skipNextResumeReload = true
        webViewManagedAuthInProgress = false
        Log.d("W2N_AUTH", "│ isCustomScheme=$isCustomScheme isWebsiteLink=$isWebsiteLink")
        Log.d("W2N_AUTH", "│ returnTo=$returnTo")
        Log.d("W2N_AUTH", "│ callbackKeys=${uri.queryParameterNames.filter { it != "returnTo" }}")
        Log.d("W2N_AUTH", "│ baseTargetUrl=$baseTargetUrl")
        Log.d("W2N_AUTH", "│ targetUrl=$targetUrl")
        Log.d("W2N_AUTH", "│ skipNextResumeReload=$skipNextResumeReload")
        Log.d("W2N_AUTH", "└─ Loading targetUrl in WebView NOW")
        postAuthDiagnostic("auth_return_loading_target", mapOf(
            "isCustomScheme" to isCustomScheme.toString(),
            "isWebsiteLink" to isWebsiteLink.toString(),
            "returnTo" to returnTo,
            "baseTargetUrl" to baseTargetUrl,
            "targetUrl" to targetUrl,
            "callbackKeys" to uri.queryParameterNames.filter { it != "returnTo" }.joinToString(","),
        ))
        clearLastCustomTabState()
        webView.post { webView.loadUrl(targetUrl) }
        return true
    }

    /**
     * Handle deep link from web2native://auth — auto-closes CCT and restores auth in WebView.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        Log.d("W2N_AUTH", "══ onNewIntent ══ data=${intent.data} action=${intent.action}")
        postAuthDiagnostic("on_new_intent", mapOf(
            "intentData" to intent.data?.toString(),
            "intentAction" to intent.action,
            "intentScheme" to intent.data?.scheme,
            "intentHost" to intent.data?.host,
            "wasInBackground" to wasInBackground.toString(),
            "skipNextResumeReload" to skipNextResumeReload.toString(),
            "webViewManagedAuth" to webViewManagedAuthInProgress.toString(),
            "lastCustomTabUrl" to lastCustomTabUrl,
            "lastCustomTabReason" to lastCustomTabReason,
            "lastCustomTabAgeMs" to lastCustomTabAgeMs(),
        ))
        if (!handleAuthReturnIntent(intent)) {
            Log.d("W2N_AUTH", "onNewIntent did not contain an auth return URL")
            postAuthDiagnostic("on_new_intent_not_auth", mapOf(
                "intentData" to intent.data?.toString(),
            ))
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        // === SPLASH-OFF: install Android 12+ SplashScreen to swap to Material theme ===
        // When splash is disabled, MainActivity is the launcher and its manifest theme is
        // Theme.WebViewApp.Splash (extends Theme.SplashScreen, NOT Material). Without
        // installSplashScreen() the post-splash theme never applies and AppCompatActivity
        // crashes with "You need to use a Theme.AppCompat theme (or descendant)".
        try {
            val sd = try { BuildConfig.SPLASH_DESIGN } catch (_: Exception) { "dots" }
            val splashOff = BuildConfig.SPLASH_TEXT.isEmpty() && (sd.isEmpty() || sd == "none")
            if (splashOff) {
                installSplashScreen()
            }
        } catch (_: Throwable) { /* best effort */ }
        super.onCreate(savedInstanceState)

        // === PREDICTIVE BACK (Android 13+) ===
        // The manifest sets android:enableOnBackInvokedCallback="true", so on API 33+
        // the system routes back through OnBackInvokedDispatcher and stops calling
        // onKeyDown/onBackPressed. Register a callback via the androidx dispatcher so
        // back navigates the WebView history instead of closing the app on all versions.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (this@MainActivity::webView.isInitialized && webView.canGoBack()) {
                    webView.goBack()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })

        // === SPLASH-ON HANDOFF ===
        // If a custom splash is configured, paint the entire window (background +
        // status + nav bars) with the splash color BEFORE inflating the layout, so
        // the user never sees a theme-color flash between SplashActivity and the
        // first WebView paint. Splash-off path is untouched (splashActiveEarly==false).
        val splashDesignEarly = try { BuildConfig.SPLASH_DESIGN } catch (_: Exception) { "dots" }
        val splashActiveEarly = BuildConfig.SPLASH_TEXT.isNotEmpty() ||
            (splashDesignEarly.isNotEmpty() && splashDesignEarly != "none")
        val splashBgColorEarly: Int = if (splashActiveEarly) {
            val splashColorStr = try { BuildConfig.SPLASH_COLOR } catch (_: Exception) { "" }
            val themeColorEarly = try { Color.parseColor(BuildConfig.THEME_COLOR) } catch (_: Exception) { Color.parseColor("#1D4ED8") }
            if (splashColorStr.isNotEmpty()) {
                try { Color.parseColor(splashColorStr) } catch (_: Exception) { themeColorEarly }
            } else themeColorEarly
        } else 0
        if (splashActiveEarly) {
            try {
                window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(splashBgColorEarly))
                window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
                window.statusBarColor = splashBgColorEarly
                // Navigation bar: keep system default (do NOT paint).
                applySystemDefaultNavBar()
                val lum = (0.299 * Color.red(splashBgColorEarly) + 0.587 * Color.green(splashBgColorEarly) + 0.114 * Color.blue(splashBgColorEarly)) / 255.0
                val lightBg = lum > 0.5
                val controller = WindowCompat.getInsetsController(window, window.decorView)
                controller.isAppearanceLightStatusBars = lightBg
            } catch (_: Exception) { }
        }

        // Detect missing/disabled Android System WebView before we touch any WebView API.
        // On Huawei (no-GMS) and some heavily-modded ROMs the WebView provider can be null;
        // creating a WebView in that state crashes. Show a friendly recovery screen instead.
        if (!isWebViewProviderAvailable()) {
            showWebViewMissingScreen()
            return
        }

        setContentView(R.layout.activity_main)

        // Handle system bar insets — works consistently on Android 10–16, including
        // Android 15+ which forces edge-to-edge regardless of setDecorFitsSystemWindows.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val rootContentForInsets = findViewById<RelativeLayout>(R.id.rootContent)
        val statusBarBgView = findViewById<View>(R.id.statusBarBg)
        // Compute the color we want to paint behind the status bar (theme or splash).
        val themeColorForBars = try {
            Color.parseColor(BuildConfig.THEME_COLOR)
        } catch (_: Exception) {
            Color.parseColor("#1D4ED8")
        }
        val statusBarBandColor = themeColorForBars
        if (BuildConfig.FULL_SCREEN) {
            ViewCompat.setOnApplyWindowInsetsListener(rootContentForInsets) { view, _ ->
                view.setPadding(0, 0, 0, 0)
                WindowInsetsCompat.CONSUMED
            }
            // No status bar band in immersive mode.
            statusBarBgView?.let {
                val lp = it.layoutParams
                lp.height = 0
                it.layoutParams = lp
            }
        } else {
            // Pad rootContent so app UI sits between status bar and nav bar on every device.
            // Add IME inset to bottom so the keyboard pushes content up (works with adjustResize).
            ViewCompat.setOnApplyWindowInsetsListener(rootContentForInsets) { view, insets ->
                val sys = insets.getInsets(WindowInsetsCompat.Type.systemBars())
                val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
                view.setPadding(sys.left, sys.top, sys.right, maxOf(sys.bottom, ime.bottom))
                // Resize the status-bar background band to match the actual top inset
                // and paint it with the user-selected color. This guarantees the color
                // is visible on Android 15+ where window.statusBarColor is ignored.
                statusBarBgView?.let { bg ->
                    val lp = bg.layoutParams
                    if (lp.height != sys.top) {
                        lp.height = sys.top
                        bg.layoutParams = lp
                    }
                    bg.setBackgroundColor(statusBarBandColor)
                }
                WindowInsetsCompat.CONSUMED
            }
        }

        // === Runtime BuildConfig verification ===
        Log.i("APP_CONFIG", "=== BUILDCONFIG VALUES AT RUNTIME ===")
        Log.i("APP_CONFIG", "SHOW_APP_BAR=" + BuildConfig.SHOW_APP_BAR)
        Log.i("APP_CONFIG", "FULL_SCREEN=" + BuildConfig.FULL_SCREEN)
        Log.i("APP_CONFIG", "SPLASH_TEXT=" + BuildConfig.SPLASH_TEXT)
        Log.i("APP_CONFIG", "SPLASH_ANIMATION=" + BuildConfig.SPLASH_ANIMATION)
        try { Log.i("APP_CONFIG", "SPLASH_DESIGN=" + BuildConfig.SPLASH_DESIGN) } catch (e: Exception) { Log.i("APP_CONFIG", "SPLASH_DESIGN=MISSING") }
        try { Log.i("APP_CONFIG", "SPLASH_COLOR=" + BuildConfig.SPLASH_COLOR) } catch (e: Exception) { Log.i("APP_CONFIG", "SPLASH_COLOR=MISSING") }
        Log.i("APP_CONFIG", "PROJECT_ID=" + BuildConfig.PROJECT_ID)
        Log.i("APP_CONFIG", "API_BASE_URL length=" + BuildConfig.API_BASE_URL.length)
        Log.i("APP_CONFIG", "SUPABASE_ANON_KEY length=" + BuildConfig.SUPABASE_ANON_KEY.length)
        Log.i("APP_CONFIG", "THEME_COLOR=" + BuildConfig.THEME_COLOR)
        Log.i("APP_CONFIG", "WEBSITE_URL=" + BuildConfig.WEBSITE_URL)
        Log.i("APP_CONFIG", "APPLICATION_ID=" + BuildConfig.APPLICATION_ID)
        Log.i("APP_CONFIG", "VERSION_NAME=" + BuildConfig.VERSION_NAME)
        Log.i("APP_CONFIG", "=== END BUILDCONFIG VALUES ===")

        // Register file chooser result handler
        fileChooserLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val results = if (result.resultCode == Activity.RESULT_OK) {
                val dataUri = result.data?.data
                if (dataUri != null) {
                    arrayOf(dataUri)
                } else if (cameraPhotoPath != null) {
                    arrayOf(Uri.parse(cameraPhotoPath))
                } else null
            } else null
            fileUploadCallback?.onReceiveValue(results ?: arrayOf())
            fileUploadCallback = null
        }

        // Apply dynamic theme color to status bar (legacy fallback for <Android 15).
        // Navigation bar is left to the OS (transparent + contrast enforced) so it matches system default.
        val themeColor = themeColorForBars
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
        if (BuildConfig.FULL_SCREEN) {
            window.statusBarColor = Color.TRANSPARENT
            window.navigationBarColor = Color.TRANSPARENT
        } else if (splashActiveEarly) {
            // Post-splash: status bar must switch to the user-selected theme color immediately.
            window.statusBarColor = themeColor
            applySystemDefaultNavBar()
            try {
                val themeLum = (0.299 * Color.red(themeColor) + 0.587 * Color.green(themeColor) + 0.114 * Color.blue(themeColor)) / 255.0
                val controller = WindowCompat.getInsetsController(window, window.decorView)
                controller.isAppearanceLightStatusBars = themeLum > 0.5
            } catch (_: Exception) { }
        } else {
            window.statusBarColor = themeColor
            applySystemDefaultNavBar()
            try {
                val themeLum = (0.299 * Color.red(themeColor) + 0.587 * Color.green(themeColor) + 0.114 * Color.blue(themeColor)) / 255.0
                val lightBg = themeLum > 0.5
                val controller = WindowCompat.getInsetsController(window, window.decorView)
                controller.isAppearanceLightStatusBars = lightBg
            } catch (_: Exception) { }
        }

        // Apply orientation lock
        requestedOrientation = when (BuildConfig.ORIENTATION) {
            "portrait" -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            "landscape" -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            else -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }

        // Apply full-screen / immersive mode
        if (BuildConfig.FULL_SCREEN) {
            Log.i("APP_CONFIG", "Applying FULL_SCREEN mode")
            window.setFlags(
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            )
            // Render into display cutout area on Android 9+ for consistent edge-to-edge across notched devices
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                try {
                    val lp = window.attributes
                    lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                    window.attributes = lp
                } catch (_: Exception) { }
            }
            applyFullScreenMode()
        }

        // Handle app bar visibility
        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        val appBarTitle = findViewById<TextView>(R.id.appBarTitle)

        webView = findViewById(R.id.webView)
        progressBar = findViewById(R.id.progressBar)
        swipeRefresh = findViewById(R.id.swipeRefresh)
        loadingOverlay = findViewById(R.id.loadingOverlay)

        val debugConfigText = findViewById<TextView>(R.id.debugConfigText)
        debugConfigText.visibility = View.GONE

        // Branding watermark: HIDDEN by default. Only render if a previous API call
        // explicitly confirmed this project is on the free tier (and Launch Pass is inactive).
        // The build-time FREE_TIER flag is intentionally NOT trusted here so that an APK
        // built while the user was free does not flash branding after they upgrade.
        val brandingPrefs = getSharedPreferences("web2native_prefs", Context.MODE_PRIVATE)
        val apiConfirmed = brandingPrefs.getBoolean("branding_api_confirmed", false)
        val cachedBranding = apiConfirmed && brandingPrefs.getBoolean("branding_visible", false)
        Log.d("W2N_BRAND", "FREE_TIER=${try { BuildConfig.FREE_TIER } catch (_: Exception) { "MISSING" }}, apiConfirmed=$apiConfirmed, cachedBranding=$cachedBranding")
        val brandingWatermark = findViewById<TextView>(R.id.brandingWatermark)
        applyBrandingVisibility(brandingWatermark, cachedBranding)

        // Defer non-critical network calls until after the website loads
        // so WebView gets full network priority on first launch
        val deferHandler = android.os.Handler(android.os.Looper.getMainLooper())
        deferHandler.postDelayed({
            checkBrandingStatus(brandingWatermark, cachedBranding)
            schedulePeriodicBrandingCheck(brandingWatermark)
        }, 3000)

        // Trial overlay — initialize and run check quickly so day-3 hard block paints early
        trialOverlayManager = TrialOverlayManager(this)
        deferHandler.postDelayed({
            trialOverlayManager.checkTrialStatus()
        }, 400)

        val splashDesign = try { BuildConfig.SPLASH_DESIGN } catch (_: Exception) { "dots" }
        val splashActive = BuildConfig.SPLASH_TEXT.isNotEmpty() || (splashDesign.isNotEmpty() && splashDesign != "none")

        Log.i("APP_CONFIG", "Header decision: SHOW_APP_BAR=${BuildConfig.SHOW_APP_BAR}, FULL_SCREEN=${BuildConfig.FULL_SCREEN}, result=${if (BuildConfig.SHOW_APP_BAR && !BuildConfig.FULL_SCREEN) "VISIBLE" else "GONE"}")

        if (BuildConfig.SHOW_APP_BAR && !BuildConfig.FULL_SCREEN) {
            toolbar.visibility = View.VISIBLE
            toolbar.setBackgroundColor(themeColor)
            appBarTitle.text = getString(R.string.app_name)
            Log.i("APP_CONFIG", "Toolbar set to VISIBLE")
        } else {
            toolbar.visibility = View.GONE
            Log.i("APP_CONFIG", "Toolbar set to GONE")
            (toolbar.layoutParams as? RelativeLayout.LayoutParams)?.let { params ->
                params.height = 0
                toolbar.layoutParams = params
            }

            (progressBar.layoutParams as? RelativeLayout.LayoutParams)?.let { params ->
                params.removeRule(RelativeLayout.BELOW)
                params.addRule(RelativeLayout.ALIGN_PARENT_TOP)
                progressBar.layoutParams = params
            }

            (webView.layoutParams as? RelativeLayout.LayoutParams)?.let { params ->
                params.removeRule(RelativeLayout.BELOW)
                params.addRule(RelativeLayout.ALIGN_PARENT_TOP)
                webView.layoutParams = params
            }

            (loadingOverlay?.layoutParams as? RelativeLayout.LayoutParams)?.let { params ->
                params.removeRule(RelativeLayout.BELOW)
                params.addRule(RelativeLayout.ALIGN_PARENT_TOP)
                loadingOverlay?.layoutParams = params
            }
        }

        // Splash-off must not show any native interstitial screen. Reveal the
        // WebView immediately and keep the loading overlay hidden.
        val loadingIcon = findViewById<ImageView>(R.id.loadingIcon)
        val loadingSpinner = findViewById<ProgressBar>(R.id.loadingSpinner)
        if (splashActive) {
            // Plain splash-colored cover until the page is fully loaded.
            // No icon, no spinner — the splash stays visually identical to
            // SplashActivity, then we cut directly to the rendered app.
            loadingOverlay?.setBackgroundColor(splashBgColorEarly)
            (loadingOverlay?.layoutParams as? RelativeLayout.LayoutParams)?.let { params ->
                params.removeRule(RelativeLayout.BELOW)
                params.addRule(RelativeLayout.ALIGN_PARENT_TOP)
                loadingOverlay?.layoutParams = params
            }
            loadingOverlay?.visibility = View.VISIBLE
            loadingIcon?.visibility = View.GONE
            loadingSpinner?.visibility = View.GONE
            Log.i("APP_CONFIG", "Loading overlay: full-screen splash bg until full page load")
        } else {
            loadingOverlay?.clearAnimation()
            loadingOverlay?.visibility = View.GONE
            loadingIcon?.visibility = View.GONE
            loadingSpinner?.visibility = View.GONE
            webView.visibility = View.VISIBLE
            Log.i("APP_CONFIG", "Loading overlay: GONE (splash disabled)")
        }

        // Keep the WebView background matching the splash so any pre-paint
        // frame stays seamless; splash-off goes straight to transparent.
        webView.setBackgroundColor(if (splashActive) splashBgColorEarly else Color.TRANSPARENT)

        Log.i("APP_CONFIG", "splashActive=$splashActive, splashDesign=$splashDesign")

        // Cookie & session persistence
        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(webView, true)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            javaScriptCanOpenWindowsAutomatically = true
            setSupportMultipleWindows(true)
            loadWithOverviewMode = true
            useWideViewPort = true
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            allowFileAccess = true
            allowContentAccess = true
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE

            // Custom User-Agent: strip WebView marker so OAuth providers
            // don't block sign-in with disallowed_useragent.
            val defaultUA = userAgentString
            val cleanUA = defaultUA.replace("; wv)", ")")
            userAgentString = "$cleanUA WebToNative/1.0"

            // Offline caching strategy
            databaseEnabled = true
            cacheMode = WebSettings.LOAD_CACHE_ELSE_NETWORK
            // Speed up first paint
            @Suppress("DEPRECATION")
            setRenderPriority(WebSettings.RenderPriority.HIGH)
        }
        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null)

        // JavaScript bridge — allows websites to detect native app
        webView.addJavascriptInterface(WebToNativeBridge(), "WebToNative")
        // Add-on Pack v1 — native share bridge (zero-config for site authors)
        if (BuildConfig.NATIVE_SHARE_ENABLED) {
            webView.addJavascriptInterface(NativeBridge(this), NativeBridge.NAME)
        }

        // File download support via DownloadManager
        webView.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            try {
                val request = DownloadManager.Request(Uri.parse(url)).apply {
                    setMimeType(mimeType)
                    addRequestHeader("User-Agent", userAgent)
                    val fileName = URLUtil.guessFileName(url, contentDisposition, mimeType)
                    setTitle(fileName)
                    setDescription("Downloading file...")
                    setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
                }
                val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                dm.enqueue(request)
                Toast.makeText(this, "Downloading...", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this, "Download failed", Toast.LENGTH_SHORT).show()
                openInChromeCustomTab(url)
            }
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url?.toString() ?: return false
                Log.d("W2N_NAV", "WebViewClient.shouldOverrideUrlLoading: $url")
                // Add-on Pack v1: URL policy decides first; fall through when "auto".
                UrlPolicy.shouldOpenExternally(url)?.let { ext ->
                    if (ext) {
                        Log.d("W2N_NAV", "URL policy → external: $url")
                        return try { openInChromeCustomTab(url, "url_policy"); true } catch (_: Throwable) { false }
                    } else {
                        Log.d("W2N_NAV", "URL policy → in-app: $url")
                        return false
                    }
                }
                return shouldOverrideNavigation(url)
            }

            override fun onPageCommitVisible(view: WebView?, url: String?) {
                super.onPageCommitVisible(view, url)
                // Splash-off: reveal WebView at first paint (legacy behavior).
                // Splash-on: keep the splash-colored overlay until onPageFinished
                // so the user goes straight from splash to the fully-rendered app.
                if (!splashActiveEarly && !pageLoaded) {
                    pageLoaded = true
                    webView.visibility = View.VISIBLE
                    webView.setBackgroundColor(Color.TRANSPARENT)
                    loadingOverlay?.let { overlay ->
                        overlay.clearAnimation()
                        overlay.visibility = View.GONE
                    }
                }
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                super.onPageStarted(view, url, favicon)
                // Inject preconnect/dns-prefetch hints to speed up sub-resource loads.
                // Safe no-op if <head> isn't ready yet — script retries once on DOMContentLoaded.
                try {
                    val origin = try { java.net.URI(url ?: "").let { "${it.scheme}://${it.host}" } } catch (_: Exception) { "" }
                    if (origin.isNotEmpty()) {
                        val js = """
                            (function(){
                              function add(rel, href){
                                try{
                                  if(document.querySelector('link[rel="'+rel+'"][href="'+href+'"]'))return;
                                  var l=document.createElement('link');l.rel=rel;l.href=href;
                                  if(rel==='preconnect')l.crossOrigin='';
                                  (document.head||document.documentElement).appendChild(l);
                                }catch(e){}
                              }
                              function inject(){
                                add('preconnect','$origin');
                                add('dns-prefetch','$origin');
                                add('preconnect','https://fonts.googleapis.com');
                                add('preconnect','https://fonts.gstatic.com');
                                add('dns-prefetch','https://www.google-analytics.com');
                                add('dns-prefetch','https://www.googletagmanager.com');
                              }
                              inject();
                              if(document.readyState==='loading'){document.addEventListener('DOMContentLoaded',inject,{once:true});}
                            })();
                        """.trimIndent()
                        view?.evaluateJavascript(js, null)
                    }
                } catch (_: Exception) { }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                CookieManager.getInstance().flush()
                Log.d("W2N_NAV", "onPageFinished: $url managedAuth=$webViewManagedAuthInProgress")

                // Auto-inject viewport meta tag for sites that lack one (renders desktop layout otherwise).
                // Guarded: no-op if site already has viewport. Re-checks after 500ms for SPAs that re-hydrate <head>.
                if (BuildConfig.AUTO_INJECT_VIEWPORT) {
                    view?.evaluateJavascript(VIEWPORT_INJECTION_JS, null)
                }

                // Add-on Pack v1: auto-upgrade navigator.share() to the native bridge.
                if (BuildConfig.NATIVE_SHARE_ENABLED) {
                    try { view?.evaluateJavascript(NativeBridge.SHIM_JS, null) } catch (_: Throwable) { }
                }

                // Remote telemetry for page loads during/after OAuth
                if (webViewManagedAuthInProgress || lastCustomTabReason == "oauth") {
                    postAuthDiagnostic("page_finished_during_auth", mapOf(
                        "url" to url,
                        "managedAuth" to webViewManagedAuthInProgress.toString(),
                        "lastCustomTabReason" to lastCustomTabReason,
                        "lastCustomTabUrl" to lastCustomTabUrl,
                        "lastCustomTabAgeMs" to lastCustomTabAgeMs(),
                        "skipNextResumeReload" to skipNextResumeReload.toString(),
                    ))
                }

                maybeCompleteManagedWebViewAuth(url)

                // Smart interstitial ad trigger
                pageLoadCount++
                val now = System.currentTimeMillis()
                if (admobEnabled && BuildConfig.ADMOB_INTERSTITIAL_ID.isNotEmpty() &&
                    pageLoadCount >= MIN_PAGE_LOADS_FOR_INTERSTITIAL &&
                    now - lastInterstitialTime > INTERSTITIAL_COOLDOWN_MS &&
                    now - appStartTime > MIN_SESSION_TIME_MS) {
                    showInterstitialAd()
                    lastInterstitialTime = now
                    pageLoadCount = 0 // Reset so next interstitial also requires engagement
                }

                // Re-show banner after cooldown
                if (admobEnabled && bannerDismissedAt > 0 &&
                    now - bannerDismissedAt > BANNER_COOLDOWN_MS) {
                    bannerDismissedAt = 0
                    val bannerContainer = findViewById<FrameLayout>(R.id.bannerAdContainer)
                    bannerContainer?.let { container ->
                        container.removeAllViews()
                        val adView = AdView(this@MainActivity)
                        val dm = resources.displayMetrics
                        val adWidth = (dm.widthPixels / dm.density).toInt()
                        val adSize = AdSize.getCurrentOrientationAnchoredAdaptiveBannerAdSize(this@MainActivity, adWidth)
                        adView.setAdSize(adSize)
                        adView.adUnitId = BuildConfig.ADMOB_BANNER_ID
                        container.addView(adView)
                        adView.loadAd(AdRequest.Builder().build())
                        // Re-add close button
                        val closeBtn = TextView(this@MainActivity).apply {
                            text = "✕"
                            textSize = 14f
                            setTextColor(Color.WHITE)
                            setBackgroundColor(Color.argb(180, 0, 0, 0))
                            setPadding(12, 4, 12, 4)
                            setOnClickListener {
                                container.visibility = View.GONE
                                bannerDismissedAt = System.currentTimeMillis()
                            }
                        }
                        val closeParams = FrameLayout.LayoutParams(
                            FrameLayout.LayoutParams.WRAP_CONTENT,
                            FrameLayout.LayoutParams.WRAP_CONTENT
                        ).apply {
                            gravity = android.view.Gravity.END or android.view.Gravity.TOP
                        }
                        container.addView(closeBtn, closeParams)
                        container.visibility = View.VISIBLE
                        Log.d("W2N_ADMOB", "Banner re-shown after cooldown")
                    }
                }

                // Reveal WebView and hide loading overlay
                if (!pageLoaded) {
                    pageLoaded = true
                    webView.visibility = View.VISIBLE
                    webView.setBackgroundColor(Color.TRANSPARENT)
                    loadingOverlay?.let { overlay ->
                        if (overlay.visibility == View.VISIBLE) {
                            if (splashActiveEarly) {
                                // Splash-on: cut directly to the app — no fade
                                // (a fade would expose the themeColor toolbar
                                // underneath for the duration of the animation).
                                overlay.clearAnimation()
                                overlay.visibility = View.GONE
                                if (!BuildConfig.FULL_SCREEN) {
                                    try {
                                        window.statusBarColor = themeColor
                                        val themeLum = (0.299 * Color.red(themeColor) + 0.587 * Color.green(themeColor) + 0.114 * Color.blue(themeColor)) / 255.0
                                        val controller = WindowCompat.getInsetsController(window, window.decorView)
                                        controller.isAppearanceLightStatusBars = themeLum > 0.5
                                        // Repaint the status-bar background band so Android 15+
                                        // shows the theme color (not the leftover splash color).
                                        findViewById<View>(R.id.statusBarBg)?.setBackgroundColor(themeColor)
                                        // Clear the splash window background without replacing it with
                                        // theme color. On Android 15+ the nav bar is transparent/edge-to-edge,
                                        // so any opaque window background becomes the visible nav-bar band.
                                        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
                                        applySystemDefaultNavBar()
                                    } catch (_: Exception) { }
                                }
                            } else {
                                val fadeOut = AlphaAnimation(1f, 0f).apply { duration = 300; fillAfter = true }
                                overlay.startAnimation(fadeOut)
                                overlay.postDelayed({ overlay.visibility = View.GONE }, 300)
                            }
                        }
                    }
                    checkForAppUpdate()
                }
            }

            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                if (request?.isForMainFrame == true && !isNetworkAvailable()) {
                    // Hide loading overlay so offline page is visible
                    loadingOverlay?.visibility = View.GONE
                    webView.visibility = View.VISIBLE
                    view?.loadUrl("file:///android_asset/offline.html")
                }
            }

            override fun onReceivedHttpError(view: WebView?, request: WebResourceRequest?, errorResponse: WebResourceResponse?) {
                if (request?.isForMainFrame == true && !isNetworkAvailable()) {
                    loadingOverlay?.visibility = View.GONE
                    webView.visibility = View.VISIBLE
                    view?.loadUrl("file:///android_asset/offline.html")
                }
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            // HTML5 Geolocation: forward to OS so "use my location" features work.
            override fun onGeolocationPermissionsShowPrompt(
                origin: String?,
                callback: android.webkit.GeolocationPermissions.Callback?
            ) {
                if (callback == null || origin == null) return
                val hasFine = ContextCompat.checkSelfPermission(
                    this@MainActivity, Manifest.permission.ACCESS_FINE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED
                val hasCoarse = ContextCompat.checkSelfPermission(
                    this@MainActivity, Manifest.permission.ACCESS_COARSE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED
                callback.invoke(origin, hasFine || hasCoarse, false)
            }

            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                if (!pageLoaded) {
                    if (newProgress < 100) {
                        progressBar.visibility = View.VISIBLE
                        progressBar.progress = newProgress
                    } else {
                        progressBar.visibility = View.GONE
                    }
                }
            }

            override fun onCreateWindow(
                view: WebView?,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: android.os.Message?
            ): Boolean {
                if (!isUserGesture) {
                    Log.w("W2N_AUTH", "Popup requested without explicit user gesture")
                }

                val popupWebView = WebView(this@MainActivity).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.javaScriptCanOpenWindowsAutomatically = true
                    settings.setSupportMultipleWindows(true)
                    settings.loadWithOverviewMode = true
                    settings.useWideViewPort = true
                    settings.allowFileAccess = true
                    settings.allowContentAccess = true
                    settings.mediaPlaybackRequiresUserGesture = false
                    settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                    settings.userAgentString = this@MainActivity.webView.settings.userAgentString
                }

                CookieManager.getInstance().setAcceptCookie(true)
                CookieManager.getInstance().setAcceptThirdPartyCookies(popupWebView, true)

                val popupDialog = android.app.Dialog(
                    this@MainActivity,
                    android.R.style.Theme_Black_NoTitleBar_Fullscreen
                )

                popupWebView.webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                        val url = request?.url?.toString() ?: return false
                        val stickyManagedAuth = webViewManagedAuthInProgress || isManagedAuthCooldownActive()

                        if (stickyManagedAuth) {
                            Log.d("W2N_AUTH", "Popup URL during sticky managed auth — forcing main WebView")
                            postAuthDiagnostic("popup_forced_into_managed_webview", mapOf(
                                "url" to url,
                                "stickyManagedAuth" to stickyManagedAuth.toString(),
                            ))
                            this@MainActivity.webView.loadUrl(url)
                            popupDialog.dismiss()
                            return true
                        }

                        val uri = Uri.parse(url)
                        val baseHost = Uri.parse(BuildConfig.WEBSITE_URL).host
                        val sameDomain = isSameDomain(uri.host, baseHost)
                        val isAuthFlow = isOAuthUrl(url) || isOAuthStarterUrl(url)

                        if (sameDomain || isAuthFlow) {
                            Log.d("W2N_AUTH", "Popup auth/same-domain URL — promoting to main WebView")
                            if (isAuthFlow) beginManagedWebViewAuthFlow(url)
                            this@MainActivity.webView.loadUrl(url)
                            popupDialog.dismiss()
                            return true
                        }

                        Log.d("W2N_AUTH", "Popup external URL — opening Chrome Custom Tab")
                        openInChromeCustomTab(url)
                        popupDialog.dismiss()
                        return true
                    }

                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        CookieManager.getInstance().flush()
                        if (url.isNullOrBlank()) return

                        val stickyManagedAuth = webViewManagedAuthInProgress || isManagedAuthCooldownActive()
                        if (stickyManagedAuth) {
                            Log.d("W2N_AUTH", "Popup page during sticky managed auth — forcing main WebView")
                            postAuthDiagnostic("popup_page_forced_into_managed_webview", mapOf(
                                "url" to url,
                                "stickyManagedAuth" to stickyManagedAuth.toString(),
                            ))
                            this@MainActivity.webView.loadUrl(url)
                            popupDialog.dismiss()
                            return
                        }

                        val uri = Uri.parse(url)
                        val baseHost = Uri.parse(BuildConfig.WEBSITE_URL).host
                        val sameDomain = isSameDomain(uri.host, baseHost)
                        val isAuthFlow = isOAuthUrl(url) || isOAuthStarterUrl(url)

                        if (sameDomain || isAuthFlow) {
                            Log.d("W2N_AUTH", "Popup landed on auth/same-domain URL — promoting to main WebView")
                            if (isAuthFlow) beginManagedWebViewAuthFlow(url)
                            this@MainActivity.webView.loadUrl(url)
                            popupDialog.dismiss()
                            return
                        }

                        Log.d("W2N_AUTH", "Popup landed on external URL — opening Chrome Custom Tab")
                        openInChromeCustomTab(url)
                        popupDialog.dismiss()
                    }
                }

                popupWebView.webChromeClient = object : WebChromeClient() {
                    override fun onCloseWindow(window: WebView?) {
                        popupDialog.dismiss()
                    }
                }

                popupDialog.setContentView(popupWebView)
                popupDialog.setOnDismissListener {
                    popupWebView.stopLoading()
                    popupWebView.destroy()
                }

                val transport = resultMsg?.obj as? WebView.WebViewTransport ?: return false
                transport.webView = popupWebView
                resultMsg.sendToTarget()
                Log.d("W2N_AUTH", "Popup window created")
                return true
            }

            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                fileUploadCallback?.onReceiveValue(null)
                fileUploadCallback = filePathCallback

                // Check camera permission before including camera intent
                val hasCameraPermission = ContextCompat.checkSelfPermission(
                    this@MainActivity, Manifest.permission.CAMERA
                ) == PackageManager.PERMISSION_GRANTED

                if (!hasCameraPermission) {
                    // Store params and request permission; flow continues in launcher callback
                    pendingFileChooserParams = fileChooserParams
                    cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                    return true
                }

                launchFileChooser(fileChooserParams, cameraGranted = true)
                return true
            }
        }

        // Only allow pull-to-refresh when WebView is scrolled to the top
        webView.setOnScrollChangeListener { _, _, scrollY, _, _ ->
            swipeRefresh.isEnabled = scrollY == 0
        }

        swipeRefresh.setOnRefreshListener {
            webView.settings.cacheMode = WebSettings.LOAD_NO_CACHE
            webView.reload()
            swipeRefresh.isRefreshing = false
        }

        val handledLaunchIntent = handleAuthReturnIntent(intent)
        if (!handledLaunchIntent) {
            val launchUri = intent?.data
            val launchUrl = when {
                launchUri?.scheme == "web2native" -> launchUri.getQueryParameter("returnTo")
                isWebsiteAppLink(launchUri) -> launchUri?.toString()
                else -> null
            }
            Log.d("W2N_AUTH", "onCreate launch: intentData=${intent?.data} launchUrl=$launchUrl fallback=${BuildConfig.WEBSITE_URL}")
            webView.loadUrl(launchUrl ?: BuildConfig.WEBSITE_URL)
        } else {
            Log.d("W2N_AUTH", "onCreate consumed auth return intent before default website load")
        }

        maybeRegisterPushToken()
        // Defer install tracking so WebView gets network priority
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            trackInstallOnce()
        }, 4000)

        // Request notification permission after a short delay so user sees the app first
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            requestNotificationPermissionIfNeeded()
        }, 2000)

        // Initialize AdMob if configured (check cached runtime status first)
        val cachedAdsEnabled = getSharedPreferences("web2native_prefs", Context.MODE_PRIVATE)
            .getBoolean("ads_runtime_enabled", true)
        if (admobEnabled && cachedAdsEnabled) {
            initAdMob()
        }
        // Runtime ad status check (respects plan changes without rebuild)
        checkAdStatusRuntime()

        // Register network callback — auto-reload when connectivity returns
        registerNetworkCallback()

        // Safety timeout: forcefully reveal WebView after 15s even if onPageFinished
        // never fires (some devices / slow networks may stall silently)
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            if (!pageLoaded) {
                Log.w("APP_CONFIG", "Safety timeout: forcing WebView visible after 15s")
                pageLoaded = true
                webView.visibility = View.VISIBLE
                webView.setBackgroundColor(Color.TRANSPARENT)
                loadingOverlay?.let { overlay ->
                    if (overlay.visibility == View.VISIBLE) {
                        overlay.visibility = View.GONE
                    }
                }
            }
        }, 15000)
    }

    private fun registerNetworkCallback() {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val request = android.net.NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        cm.registerNetworkCallback(request, object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: android.net.Network) {
                // Auto-reload when network comes back
                runOnUiThread {
                    val currentUrl = if (::webView.isInitialized) webView.url else null
                    if (currentUrl?.startsWith("file:") == true) {
                        webView.settings.cacheMode = WebSettings.LOAD_CACHE_ELSE_NETWORK
                        webView.loadUrl(BuildConfig.WEBSITE_URL)
                    }
                }
            }
        })
    }

    private fun initAdMob() {
        // Skip Play Services Ads entirely when no real ad units are configured.
        // This is the case for free-tier wrapper builds and removes a whole class
        // of startup crashes on devices with outdated/partial Play Services
        // (e.g. Android Go devices like the Redmi A5).
        val hasAnyAdUnit = BuildConfig.ADMOB_BANNER_ID.isNotEmpty() ||
            BuildConfig.ADMOB_INTERSTITIAL_ID.isNotEmpty() ||
            rewardedAdUnitIdCompat.isNotEmpty()
        if (!hasAnyAdUnit) {
            Log.d("W2N_ADMOB", "AdMob init skipped: no ad units configured")
            return
        }

        // Defer the heavy SDK init slightly so the WebView gets to paint first
        // on slow devices — prevents a startup-time OOM/jank window.
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            try {
                MobileAds.initialize(this) { initStatus ->
                    Log.d("W2N_ADMOB", "AdMob initialized: $initStatus")
                }
            } catch (t: Throwable) {
                Log.w("W2N_ADMOB", "MobileAds.initialize failed safely: ${t.javaClass.simpleName}: ${t.message}")
                return@postDelayed
            }
            try { setupAdMobUnits() } catch (t: Throwable) {
                Log.w("W2N_ADMOB", "AdMob unit setup failed safely: ${t.javaClass.simpleName}: ${t.message}")
            }
        }, 1500L)
    }

    private fun setupAdMobUnits() {
        if (BuildConfig.ADMOB_BANNER_ID.isNotEmpty()) {
            val adView = AdView(this)
            // Use adaptive banner for better sizing
            val dm = resources.displayMetrics
            val adWidth = (dm.widthPixels / dm.density).toInt()
            val adSize = AdSize.getCurrentOrientationAnchoredAdaptiveBannerAdSize(this, adWidth)
            adView.setAdSize(adSize)
            adView.adUnitId = BuildConfig.ADMOB_BANNER_ID

            val bannerContainer = findViewById<FrameLayout>(R.id.bannerAdContainer)
            bannerContainer?.let { container ->
                container.visibility = View.VISIBLE
                container.addView(adView)
                adView.loadAd(AdRequest.Builder().build())

                // Add close button to banner
                val closeBtn = TextView(this).apply {
                    text = "✕"
                    textSize = 14f
                    setTextColor(Color.WHITE)
                    setBackgroundColor(Color.argb(180, 0, 0, 0))
                    setPadding(12, 4, 12, 4)
                    setOnClickListener {
                        container.visibility = View.GONE
                        bannerDismissedAt = System.currentTimeMillis()
                        Log.d("W2N_ADMOB", "Banner dismissed, will reappear after cooldown on next page load")
                    }
                }
                val closeParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    gravity = android.view.Gravity.END or android.view.Gravity.TOP
                }
                container.addView(closeBtn, closeParams)
            }
        }

        if (BuildConfig.ADMOB_INTERSTITIAL_ID.isNotEmpty()) {
            loadInterstitialAd()
        }

        if (rewardedAdUnitIdCompat.isNotEmpty()) {
            loadRewardedAd()
        }
    }

    private fun loadInterstitialAd() {
        InterstitialAd.load(
            this,
            BuildConfig.ADMOB_INTERSTITIAL_ID,
            AdRequest.Builder().build(),
            object : InterstitialAdLoadCallback() {
                override fun onAdLoaded(ad: InterstitialAd) {
                    interstitialAd = ad
                    Log.d("W2N_ADMOB", "Interstitial ad loaded")
                }
                override fun onAdFailedToLoad(error: LoadAdError) {
                    interstitialAd = null
                    Log.w("W2N_ADMOB", "Interstitial ad failed to load: ${error.message}")
                }
            }
        )
    }

    private fun showInterstitialAd() {
        val ad = interstitialAd
        if (ad != null) {
            ad.fullScreenContentCallback = object : FullScreenContentCallback() {
                override fun onAdDismissedFullScreenContent() {
                    interstitialAd = null
                    loadInterstitialAd()
                }
                override fun onAdFailedToShowFullScreenContent(error: AdError) {
                    interstitialAd = null
                    loadInterstitialAd()
                }
            }
            ad.show(this)
        } else {
            Log.w("W2N_ADMOB", "Interstitial ad not ready")
            loadInterstitialAd()
        }
    }

    private fun loadRewardedAd() {
        RewardedAd.load(
            this,
            rewardedAdUnitIdCompat,
            AdRequest.Builder().build(),
            object : RewardedAdLoadCallback() {
                override fun onAdLoaded(ad: RewardedAd) {
                    rewardedAd = ad
                    Log.d("W2N_ADMOB", "Rewarded ad loaded")
                }
                override fun onAdFailedToLoad(error: LoadAdError) {
                    rewardedAd = null
                    Log.w("W2N_ADMOB", "Rewarded ad failed to load: ${error.message}")
                }
            }
        )
    }

    private fun showRewardedAd() {
        val ad = rewardedAd
        if (ad != null) {
            ad.fullScreenContentCallback = object : FullScreenContentCallback() {
                override fun onAdDismissedFullScreenContent() {
                    rewardedAd = null
                    loadRewardedAd()
                }
                override fun onAdFailedToShowFullScreenContent(error: AdError) {
                    rewardedAd = null
                    loadRewardedAd()
                }
            }
            ad.show(this) { rewardItem ->
                val rewardType = rewardItem.type
                val rewardAmount = rewardItem.amount
                Log.d("W2N_ADMOB", "User earned reward: type=$rewardType amount=$rewardAmount")
                webView.post {
                    webView.evaluateJavascript(
                        "if(window.onRewardEarned)window.onRewardEarned('$rewardType',$rewardAmount)",
                        null
                    )
                }
            }
        } else {
            Log.w("W2N_ADMOB", "Rewarded ad not ready yet")
            webView.post {
                webView.evaluateJavascript(
                    "if(window.onRewardAdNotReady)window.onRewardAdNotReady()",
                    null
                )
            }
            loadRewardedAd()
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK && webView.canGoBack()) {
            webView.goBack()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    /**
     * When app returns from background, reload the WebView to pick up new auth session.
     */
    override fun onResume() {
        super.onResume()

        // Add-on Pack v1: maybe show the native In-App Review prompt.
        try { ReviewManager.maybeShow(this) } catch (_: Throwable) { }

        Log.d("W2N_AUTH", "┌─ onResume ──────────────────────────────")
        Log.d("W2N_AUTH", "│ wasInBackground=$wasInBackground")
        Log.d("W2N_AUTH", "│ skipNextResumeReload=$skipNextResumeReload")
        Log.d("W2N_AUTH", "│ webViewManagedAuthInProgress=$webViewManagedAuthInProgress")
        Log.d("W2N_AUTH", "│ lastCustomTabUrl=$lastCustomTabUrl")
        Log.d("W2N_AUTH", "│ lastCustomTabReason=$lastCustomTabReason")
        Log.d("W2N_AUTH", "│ lastCustomTabAgeMs=${lastCustomTabAgeMs()}")
        Log.d("W2N_AUTH", "│ pageLoaded=$pageLoaded")
        Log.d("W2N_AUTH", "│ currentWebViewUrl=${if (::webView.isInitialized) webView.url else "NOT_INIT"}")
        Log.d("W2N_AUTH", "│ intentData=${intent?.data}")

        postAuthDiagnostic("on_resume", mapOf(
            "wasInBackground" to wasInBackground.toString(),
            "skipNextResumeReload" to skipNextResumeReload.toString(),
            "webViewManagedAuth" to webViewManagedAuthInProgress.toString(),
            "lastCustomTabUrl" to lastCustomTabUrl,
            "lastCustomTabReason" to lastCustomTabReason,
            "lastCustomTabAgeMs" to lastCustomTabAgeMs(),
            "pageLoaded" to pageLoaded.toString(),
            "currentWebViewUrl" to (if (::webView.isInitialized) webView.url else "NOT_INIT"),
            "intentData" to intent?.data?.toString(),
        ))

        if (skipNextResumeReload) {
            Log.d("W2N_AUTH", "│ ACTION: skip reload (auth deep-link replay)")
            Log.d("W2N_AUTH", "└─────────────────────────────────────────")
            skipNextResumeReload = false
            wasInBackground = false
            clearLastCustomTabState()
            return
        }
        if (wasInBackground && lastCustomTabReason == "oauth") {
            Log.d("W2N_AUTH", "│ ACTION: CCT returned WITHOUT deep link — clearing auth state")
            webViewManagedAuthInProgress = false
            postAuthDiagnostic("oauth_cct_resumed_without_deeplink", mapOf(
                "lastCustomTabUrl" to lastCustomTabUrl,
                "lastCustomTabAgeMs" to lastCustomTabAgeMs(),
                "currentWebViewUrl" to (if (::webView.isInitialized) webView.url else "NOT_INIT"),
                "pageLoaded" to pageLoaded.toString(),
            ))
            clearLastCustomTabState()
        }
        // Re-check branding and ad status on every resume (not just background return)
        run {
            val brandingWatermark = findViewById<TextView>(R.id.brandingWatermark)
            val prefs = getSharedPreferences("web2native_prefs", Context.MODE_PRIVATE)
            val apiConfirmed = prefs.getBoolean("branding_api_confirmed", false)
            val cachedBranding = apiConfirmed && prefs.getBoolean("branding_visible", false)
            checkBrandingStatus(brandingWatermark, cachedBranding)
            checkAdStatusRuntime()
        }
        // Re-assert trial state on every resume so backgrounding can't escape a hard block,
        // and so a free→paid upgrade flips off the overlay the next time the user returns.
        try { if (::trialOverlayManager.isInitialized) trialOverlayManager.checkTrialStatus() } catch (_: Throwable) {}
        val backgroundDurationMs = if (wasInBackground && backgroundSinceMs > 0) System.currentTimeMillis() - backgroundSinceMs else 0L
        if (wasInBackground && pageLoaded && backgroundDurationMs > BACKGROUND_RELOAD_THRESHOLD_MS) {
            Log.d("W2N_AUTH", "│ ACTION: reload WebView to pick up session (away ${backgroundDurationMs}ms)")
            webView.post { webView.reload() }
        } else if (wasInBackground && pageLoaded) {
            Log.d("W2N_AUTH", "│ ACTION: skip reload — short background (${backgroundDurationMs}ms < ${BACKGROUND_RELOAD_THRESHOLD_MS}ms)")
        }
        Log.d("W2N_AUTH", "└─────────────────────────────────────────")
        wasInBackground = false
    }

    override fun onPause() {
        super.onPause()
        wasInBackground = true
        backgroundSinceMs = System.currentTimeMillis()
        Log.d("W2N_AUTH", "onPause — wasInBackground=true, lastCustomTabReason=$lastCustomTabReason")
        postAuthDiagnostic("on_pause", mapOf(
            "lastCustomTabUrl" to lastCustomTabUrl,
            "lastCustomTabReason" to lastCustomTabReason,
            "webViewManagedAuth" to webViewManagedAuthInProgress.toString(),
        ))
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
    }

    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        super.onRestoreInstanceState(savedInstanceState)
        webView.restoreState(savedInstanceState)
    }

    // JavaScript bridge class — exposes native capabilities to web
    inner class WebToNativeBridge {
        @JavascriptInterface
        fun isNativeApp(): Boolean = true

        @JavascriptInterface
        fun getAppVersion(): String = BuildConfig.VERSION_NAME

        @JavascriptInterface
        fun getAppPackage(): String = BuildConfig.APPLICATION_ID

        @JavascriptInterface
        fun getPlatform(): String = "android"

        @JavascriptInterface
        fun clearAuthCookies() {
            runOnUiThread {
                CookieManager.getInstance().removeAllCookies(null)
                CookieManager.getInstance().flush()
                webView.clearCache(false)
                webView.evaluateJavascript(
                    "try { sessionStorage.clear(); } catch(e) {}",
                    null
                )
                Log.d("W2N_AUTH", "Auth cookies and session storage cleared via JS bridge")
            }
        }

        @JavascriptInterface
        fun showRewardedAd() {
            runOnUiThread { this@MainActivity.showRewardedAd() }
        }

        @JavascriptInterface
        fun showInterstitialAd() {
            runOnUiThread { this@MainActivity.showInterstitialAd() }
        }

        @JavascriptInterface
        fun closeBannerAd() {
            runOnUiThread {
                findViewById<FrameLayout>(R.id.bannerAdContainer)?.visibility = View.GONE
            }
        }
    }

    private fun applyFullScreenMode() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.let {
                it.hide(android.view.WindowInsets.Type.statusBars() or android.view.WindowInsets.Type.navigationBars())
                it.systemBarsBehavior = android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            )
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && BuildConfig.FULL_SCREEN) {
            applyFullScreenMode()
        }
    }

    // Re-apply orientation + immersive on rotation, fold/unfold, multi-window, density changes
    // so behavior stays consistent across devices and Android versions.
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        try {
            requestedOrientation = when (BuildConfig.ORIENTATION) {
                "portrait" -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                "landscape" -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                else -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }
            if (BuildConfig.FULL_SCREEN) applyFullScreenMode()
        } catch (_: Exception) { }
    }

    companion object {
        // Viewport meta-tag injection script. Guarded: no-op if site already has a viewport meta tag
        // (covers `name="viewport"` AND legacy `content` containing width=device-width).
        // Re-checks once after 500ms to handle SPAs (React/Next/Vite) that wipe <head> on hydration.
        // user-scalable=yes preserves accessibility zoom (no maximum-scale lock).
        private const val VIEWPORT_INJECTION_JS = """
(function(){
  function ensure(){
    if (document.querySelector('meta[name="viewport"], meta[content*="width=device-width"]')) return false;
    var m = document.createElement('meta');
    m.name = 'viewport';
    m.content = 'width=device-width, initial-scale=1, user-scalable=yes, viewport-fit=cover';
    (document.head || document.documentElement).appendChild(m);
    return true;
  }
  try { if (ensure()) console.log('[W2N] viewport injected'); } catch(e){}
  setTimeout(function(){ try { if (ensure()) console.log('[W2N] viewport re-injected after hydration'); } catch(e){} }, 500);
})();
"""
    }
}
