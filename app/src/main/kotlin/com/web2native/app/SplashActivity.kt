package com.web2native.app

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.browser.customtabs.CustomTabsIntent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.view.View
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

// TEMPLATE_MARKER:SplashActivity_v9
class SplashActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        // Android 12+: SplashActivity uses Theme.SplashScreen-derived theme. We must
        // call installSplashScreen() before super.onCreate() so the framework swaps
        // to postSplashScreenTheme (Material). Without this, AppCompatActivity crashes
        // on stricter OEM ROMs (Samsung One UI, MIUI, Realme, Tecno).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try { installSplashScreen() } catch (_: Throwable) { }
        }
        super.onCreate(savedInstanceState)

        val splashText = BuildConfig.SPLASH_TEXT
        val splashDesign = try { BuildConfig.SPLASH_DESIGN } catch (_: Exception) { "dots" }

        Log.i("SPLASH_CONFIG", "=== SPLASH BUILDCONFIG ===")
        Log.i("SPLASH_CONFIG", "SPLASH_TEXT=" + splashText)
        Log.i("SPLASH_CONFIG", "SPLASH_DESIGN=" + splashDesign)
        Log.i("SPLASH_CONFIG", "SPLASH_ANIMATION=" + BuildConfig.SPLASH_ANIMATION)
        try { Log.i("SPLASH_CONFIG", "SPLASH_COLOR=" + BuildConfig.SPLASH_COLOR) } catch (_: Exception) { Log.i("SPLASH_CONFIG", "SPLASH_COLOR=MISSING") }
        Log.i("SPLASH_CONFIG", "=== END SPLASH CONFIG ===")

        val shouldShowSplash = splashText.isNotEmpty() || (splashDesign != "none" && splashDesign.isNotEmpty())
        Log.i("SPLASH_CONFIG", "shouldShowSplash=" + shouldShowSplash)

        if (shouldShowSplash) {
            // Compute background color BEFORE setContentView so we can apply it everywhere
            val splashColorStr = try { BuildConfig.SPLASH_COLOR } catch (_: Exception) { "" }
            val themeColor = try {
                Color.parseColor(BuildConfig.THEME_COLOR)
            } catch (_: Exception) {
                Color.parseColor("#1D4ED8")
            }
            val bgColor = if (splashColorStr.isNotEmpty()) {
                try { Color.parseColor(splashColorStr) } catch (_: Exception) { themeColor }
            } else {
                themeColor
            }

            Log.i("SPLASH_CONFIG", "bgColor=#" + Integer.toHexString(bgColor))

            // Apply background to window BEFORE inflating layout to avoid white flash
            window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
            window.statusBarColor = bgColor
            // Navigation bar: keep system default (do NOT paint).
            window.navigationBarColor = Color.TRANSPARENT
            window.setBackgroundDrawable(ColorDrawable(bgColor))

            // Apply orientation lock during splash to match MainActivity (prevents rotation flash on tablets/foldables)
            requestedOrientation = when (try { BuildConfig.ORIENTATION } catch (_: Exception) { "" }) {
                "portrait" -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                "landscape" -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                else -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }

            // Toggle light/dark status-bar icons based on splash background luminance
            // so status icons remain visible on light splash colors (white, yellow, etc.)
            try {
                val bgLuminance = (0.299 * Color.red(bgColor) + 0.587 * Color.green(bgColor) + 0.114 * Color.blue(bgColor)) / 255.0
                val lightBg = bgLuminance > 0.5
                androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, true)
                val controller = androidx.core.view.WindowCompat.getInsetsController(window, window.decorView)
                controller.isAppearanceLightStatusBars = lightBg
            } catch (_: Exception) { }

            setContentView(R.layout.activity_splash)

            // Apply background to ALL layers to ensure no white bleeds through
            findViewById<View>(android.R.id.content).setBackgroundColor(bgColor)
            val rootView = findViewById<View>(R.id.splashRoot)
            rootView?.setBackgroundColor(bgColor)

            val iconView = findViewById<ImageView>(R.id.splashIcon)
            val textView = findViewById<TextView>(R.id.splashText)
            val circularProgress = findViewById<ProgressBar>(R.id.splashProgress)
            val horizontalProgress = findViewById<ProgressBar>(R.id.splashProgressBar)
            val dotsContainer = findViewById<LinearLayout>(R.id.splashDots)
            val dots = listOf(
                findViewById<View>(R.id.splashDot1),
                findViewById<View>(R.id.splashDot2),
                findViewById<View>(R.id.splashDot3),
            )

            // Responsive sizing based on screen dimensions
            val displayMetrics = resources.displayMetrics
            val screenWidthDp = displayMetrics.widthPixels / displayMetrics.density
            val screenHeightDp = displayMetrics.heightPixels / displayMetrics.density

            // Icon: ~35% of the smaller screen dimension, clamped between 120dp and 280dp
            val minDim = minOf(screenWidthDp, screenHeightDp)
            val iconSizeDp = (minDim * 0.35f).coerceIn(120f, 280f)
            val iconSizePx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, iconSizeDp, displayMetrics).toInt()
            iconView.layoutParams = iconView.layoutParams.apply {
                width = iconSizePx
                height = iconSizePx
            }

            // Text: scale based on screen width, clamped between 22sp and 40sp
            val textSizeSp = (screenWidthDp * 0.08f).coerceIn(22f, 40f)
            textView.setTextSize(TypedValue.COMPLEX_UNIT_SP, textSizeSp)

            if (splashText.isNotEmpty()) {
                textView.text = splashText
                textView.visibility = View.VISIBLE
            } else {
                textView.visibility = View.GONE
            }

            // Text color: user-specified or auto-contrast
            val splashTextColorStr = try { BuildConfig.SPLASH_TEXT_COLOR } catch (_: Exception) { "" }
            val textColor = if (splashTextColorStr.isNotEmpty()) {
                try { Color.parseColor(splashTextColorStr) } catch (_: Exception) { getContrastColor(bgColor) }
            } else {
                getContrastColor(bgColor)
            }
            textView.setTextColor(textColor)

            // Loader color: user-specified or contrast-based (independent of text color)
            val splashLoaderColorStr = try { BuildConfig.SPLASH_LOADER_COLOR } catch (_: Exception) { "" }
            val loaderFallback = getLoaderContrastColor(bgColor)
            val dotColor = if (splashLoaderColorStr.isNotEmpty()) {
                try { Color.parseColor(splashLoaderColorStr) } catch (_: Exception) { loaderFallback }
            } else {
                loaderFallback
            }
            dots.forEach { dot ->
                dot.background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(dotColor)
                }
            }

            circularProgress.visibility = View.GONE
            horizontalProgress.visibility = View.GONE
            dotsContainer.visibility = View.GONE

            when (splashDesign) {
                "none" -> {
                    // No loading indicator
                }
                "spinner" -> {
                    circularProgress.isIndeterminate = true
                    circularProgress.visibility = View.VISIBLE
                }
                "bar" -> {
                    horizontalProgress.isIndeterminate = true
                    horizontalProgress.visibility = View.VISIBLE
                }
                else -> {
                    dotsContainer.visibility = View.VISIBLE
                    dots.forEachIndexed { index, dot ->
                        startDotAnimation(dot, (index * 120L))
                    }
                }
            }

            // Set initial alpha for animations — loader starts visible immediately
            // to ensure it's seen within the 3s splash window
            iconView.alpha = 0f
            iconView.scaleX = 0.3f
            iconView.scaleY = 0.3f
            textView.alpha = 0f
            textView.translationY = 30f

            val animationStyle = BuildConfig.SPLASH_ANIMATION

            when (animationStyle) {
                "fade" -> {
                    val iconAlpha = ObjectAnimator.ofFloat(iconView, "alpha", 0f, 1f).apply { duration = 800 }
                    val textAlpha = ObjectAnimator.ofFloat(textView, "alpha", 0f, 1f).apply { duration = 800; startDelay = 200 }
                    val textSlide = ObjectAnimator.ofFloat(textView, "translationY", 0f, 0f).apply { duration = 1 }
                    AnimatorSet().apply { playTogether(listOf(iconAlpha, textAlpha, textSlide)); start() }
                }
                "slide-up" -> {
                    val iconAlpha = ObjectAnimator.ofFloat(iconView, "alpha", 0f, 1f).apply { duration = 600 }
                    val iconSlide = ObjectAnimator.ofFloat(iconView, "translationY", 200f, 0f).apply {
                        duration = 600; interpolator = AccelerateDecelerateInterpolator()
                    }
                    val textAlpha = ObjectAnimator.ofFloat(textView, "alpha", 0f, 1f).apply { startDelay = 150; duration = 600 }
                    val textSlide = ObjectAnimator.ofFloat(textView, "translationY", 150f, 0f).apply {
                        startDelay = 150; duration = 600; interpolator = AccelerateDecelerateInterpolator()
                    }
                    AnimatorSet().apply { playTogether(listOf(iconAlpha, iconSlide, textAlpha, textSlide)); start() }
                }
                "bounce" -> {
                    iconView.translationY = -300f
                    val iconAlpha = ObjectAnimator.ofFloat(iconView, "alpha", 0f, 1f).apply { duration = 400 }
                    val iconDrop = ObjectAnimator.ofFloat(iconView, "translationY", -300f, 0f).apply {
                        duration = 700; interpolator = OvershootInterpolator(2.0f)
                    }
                    val iconScaleX = ObjectAnimator.ofFloat(iconView, "scaleX", 0.3f, 1f).apply { duration = 500 }
                    val iconScaleY = ObjectAnimator.ofFloat(iconView, "scaleY", 0.3f, 1f).apply { duration = 500 }
                    val textAlpha = ObjectAnimator.ofFloat(textView, "alpha", 0f, 1f).apply { startDelay = 500; duration = 400 }
                    val textSlide = ObjectAnimator.ofFloat(textView, "translationY", 30f, 0f).apply { startDelay = 500; duration = 400 }
                    AnimatorSet().apply { playTogether(listOf(iconAlpha, iconDrop, iconScaleX, iconScaleY, textAlpha, textSlide)); start() }
                }
                else -> {
                    val iconScaleX = ObjectAnimator.ofFloat(iconView, "scaleX", 0.3f, 1f).apply {
                        duration = 500; interpolator = OvershootInterpolator(1.5f)
                    }
                    val iconScaleY = ObjectAnimator.ofFloat(iconView, "scaleY", 0.3f, 1f).apply {
                        duration = 500; interpolator = OvershootInterpolator(1.5f)
                    }
                    val iconAlpha = ObjectAnimator.ofFloat(iconView, "alpha", 0f, 1f).apply { duration = 400 }
                    val textAlpha = ObjectAnimator.ofFloat(textView, "alpha", 0f, 1f).apply {
                        startDelay = 300; duration = 400; interpolator = AccelerateDecelerateInterpolator()
                    }
                    val textSlide = ObjectAnimator.ofFloat(textView, "translationY", 30f, 0f).apply {
                        startDelay = 300; duration = 400; interpolator = AccelerateDecelerateInterpolator()
                    }
                    AnimatorSet().apply { playTogether(listOf(iconScaleX, iconScaleY, iconAlpha, textAlpha, textSlide)); start() }
                }
            }

            // Branding watermark: HIDDEN by default. Only show when the API has explicitly
            // confirmed the project is on the free tier with no active Launch Pass.
            // This guarantees paid users never see branding for even a millisecond, even if
            // the APK was built while they were on the free tier.
            val brandingPrefs = getSharedPreferences("web2native_prefs", MODE_PRIVATE)
            val brandingApiConfirmed = brandingPrefs.getBoolean("branding_api_confirmed", false)
            val brandingVisibleCached = brandingPrefs.getBoolean("branding_visible", false)
            val showBranding = brandingApiConfirmed && brandingVisibleCached
            if (showBranding) {
                val brandingText = findViewById<TextView>(R.id.brandingText)
                brandingText?.visibility = View.VISIBLE
                brandingText?.setTextColor(Color.argb(153, Color.red(textColor), Color.green(textColor), Color.blue(textColor)))
                brandingText?.setOnClickListener {
                    try {
                        val cctIntent = CustomTabsIntent.Builder().setShowTitle(true).build()
                        cctIntent.launchUrl(this, Uri.parse("https://nativeappai.com"))
                    } catch (_: Exception) {
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://nativeappai.com")))
                    }
                }
            }

            // Pre-fetch branding status during splash so MainActivity has fresh data
            prefetchBrandingStatus()
            // Pre-warm DNS + TLS for the website URL so MainActivity's WebView loads faster
            prefetchWebsiteConnection()
            // Pre-warm Chrome Custom Tabs for instant future CCT opens
            warmupCustomTabs()
            // Pre-warm Chromium WebView process so MainActivity's WebView creates instantly
            // (W2NApplication already did this at process boot, but harmless to re-call)
            prewarmWebView()
            // Pre-load website HTML into WebView's shared HTTP cache so MainActivity's
            // WebView serves it from disk instead of going to network on first launch.
            prefetchWebsiteIntoWebViewCache()
            // Adaptive dismiss: as early as 300ms once HTML starts arriving, never later than 1200ms.
            scheduleAdaptiveSplashDismiss()
        } else {
            startMainActivity(false)
        }
    }

    private fun prewarmWebView() {
        // Construct a throwaway WebView on the main thread to force Chromium
        // process startup in parallel with the splash animation. This trims
        // ~300-800ms off MainActivity's WebView creation on first launch.
        try {
            val wv = android.webkit.WebView(applicationContext)
            wv.settings.javaScriptEnabled = true
            wv.settings.domStorageEnabled = true
            // Touch the cookie manager so it initializes too
            android.webkit.CookieManager.getInstance().setAcceptCookie(true)
            // Destroy the throwaway WebView; the Chromium process stays warm
            wv.destroy()
            Log.d("SPLASH_PREWARM", "WebView prewarm OK")
        } catch (e: Exception) {
            Log.d("SPLASH_PREWARM", "WebView prewarm skipped: ${e.message}")
        }
    }

    private val dotAnimators = mutableListOf<ObjectAnimator>()

    private fun startDotAnimation(dot: View, startDelayMs: Long) {
        dot.alpha = 0.55f

        val bounceAnim = ObjectAnimator.ofFloat(dot, "translationY", 0f, -12f, 0f).apply {
            duration = 600
            startDelay = startDelayMs
            repeatCount = ValueAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
            start()
        }
        dotAnimators.add(bounceAnim)

        val alphaAnim = ObjectAnimator.ofFloat(dot, "alpha", 0.55f, 1f, 0.55f).apply {
            duration = 600
            startDelay = startDelayMs
            repeatCount = ValueAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
            start()
        }
        dotAnimators.add(alphaAnim)
    }

    private fun getContrastColor(color: Int): Int {
        val r = Color.red(color)
        val g = Color.green(color)
        val b = Color.blue(color)
        val luminance = (0.299 * r + 0.587 * g + 0.114 * b) / 255.0
        return if (luminance > 0.5) Color.parseColor("#1E293B") else Color.WHITE
    }

    private fun getLoaderContrastColor(bgColor: Int): Int {
        val r = Color.red(bgColor)
        val g = Color.green(bgColor)
        val b = Color.blue(bgColor)
        val luminance = (0.299 * r + 0.587 * g + 0.114 * b) / 255.0
        return if (luminance > 0.5) Color.parseColor("#374151") else Color.parseColor("#E2E8F0")
    }

    private fun prefetchBrandingStatus() {
        val supabaseUrl = try { BuildConfig.API_BASE_URL } catch (_: Exception) { "" }
        val supabaseKey = try { BuildConfig.SUPABASE_ANON_KEY } catch (_: Exception) { "" }
        val projectId = try { BuildConfig.PROJECT_ID } catch (_: Exception) { "" }

        if (supabaseUrl.isEmpty() || supabaseKey.isEmpty() || projectId.isEmpty()) {
            Log.d("SPLASH_BRAND", "Missing config for branding prefetch, skipping")
            return
        }

        Thread {
            try {
                val url = URL("$supabaseUrl/functions/v1/check-branding-status")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("apikey", supabaseKey)
                conn.setRequestProperty("Authorization", "Bearer $supabaseKey")
                conn.connectTimeout = 5000
                conn.readTimeout = 5000
                conn.doOutput = true
                conn.outputStream.use { it.write("{\"project_id\":\"$projectId\"}".toByteArray()) }

                if (conn.responseCode == 200) {
                    val body = conn.inputStream.bufferedReader().readText()
                    val json = JSONObject(body)
                    val showBranding = json.optBoolean("show_branding", true)
                    val prefs = getSharedPreferences("web2native_prefs", MODE_PRIVATE)
                    prefs.edit()
                        .putBoolean("branding_visible", showBranding)
                        .putBoolean("branding_api_confirmed", true)
                        .apply()
                    Log.d("SPLASH_BRAND", "Prefetch OK: show_branding=$showBranding")
                } else {
                    Log.w("SPLASH_BRAND", "Prefetch failed: HTTP ${conn.responseCode}")
                }
                conn.disconnect()
            } catch (e: Exception) {
                Log.w("SPLASH_BRAND", "Prefetch error: ${e.message}")
            }
        }.start()
    }

    private fun prefetchWebsiteConnection() {
        val websiteUrl = try { BuildConfig.WEBSITE_URL } catch (_: Exception) { "" }
        if (websiteUrl.isEmpty()) return
        Thread {
            try {
                val url = URL(websiteUrl)
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "HEAD"
                conn.connectTimeout = 3000
                conn.readTimeout = 1000
                conn.instanceFollowRedirects = true
                conn.connect()
                conn.disconnect()
                Log.d("SPLASH_PREFETCH", "DNS+TLS prefetch OK for $websiteUrl")
            } catch (e: Exception) {
                Log.d("SPLASH_PREFETCH", "Prefetch attempt: ${e.message}")
            }
        }.start()
    }

    private fun warmupCustomTabs() {
        try {
            androidx.browser.customtabs.CustomTabsClient.connectAndInitialize(this, "com.android.chrome")
            Log.d("SPLASH_CCT", "Chrome Custom Tabs warmed up")
        } catch (_: Exception) {
            Log.d("SPLASH_CCT", "CCT warmup skipped (Chrome not available)")
        }
    }

    // --- First-launch performance helpers ---

    private var prefetchWebView: android.webkit.WebView? = null
    private var splashDismissed = false
    private val splashStartedAtMs = System.currentTimeMillis()
    // Hold the full splash (icon + text + loader) until the website is fully
    // loaded in the hidden prefetch WebView, with a hard safety cap so a slow
    // or broken site can never trap the user on the splash.
    private val SPLASH_MIN_MS = 600L
    private val SPLASH_MAX_HOLD_MS = 8000L

    private fun prefetchWebsiteIntoWebViewCache() {
        val websiteUrl = try { BuildConfig.WEBSITE_URL } catch (_: Exception) { "" }
        if (websiteUrl.isEmpty()) return
        try {
            val wv = android.webkit.WebView(applicationContext)
            wv.settings.javaScriptEnabled = true
            wv.settings.domStorageEnabled = true
            wv.settings.cacheMode = android.webkit.WebSettings.LOAD_DEFAULT
            val ua = wv.settings.userAgentString.replace("; wv)", ")")
            wv.settings.userAgentString = "$ua WebToNative/1.0"
            wv.webViewClient = object : android.webkit.WebViewClient() {
                override fun onPageFinished(view: android.webkit.WebView?, url: String?) {
                    val elapsed = System.currentTimeMillis() - splashStartedAtMs
                    val wait = (SPLASH_MIN_MS - elapsed).coerceAtLeast(0)
                    Log.d("SPLASH_PREFETCH_WV", "HTML finished after ${elapsed}ms, dismissing in ${wait}ms")
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ dismissSplash() }, wait)
                }
                override fun onReceivedError(
                    view: android.webkit.WebView?,
                    request: android.webkit.WebResourceRequest?,
                    error: android.webkit.WebResourceError?
                ) {
                    if (request?.isForMainFrame == true) {
                        Log.d("SPLASH_PREFETCH_WV", "Main-frame error, dismissing splash so MainActivity can show offline page")
                        android.os.Handler(android.os.Looper.getMainLooper()).post { dismissSplash() }
                    }
                }
            }
            prefetchWebView = wv
            wv.loadUrl(websiteUrl)
        } catch (e: Exception) {
            Log.d("SPLASH_PREFETCH_WV", "skipped: ${e.message}")
            // If the prefetch can't even start, fall back to the safety timeout only.
        }
    }

    private fun scheduleAdaptiveSplashDismiss() {
        // Hard safety cap only — normal dismissal is driven by the prefetch
        // WebView's onPageFinished so the rich splash stays visible until the
        // website is fully loaded.
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            if (!splashDismissed) {
                Log.w("SPLASH_PREFETCH_WV", "Safety timeout reached after ${SPLASH_MAX_HOLD_MS}ms, dismissing splash")
            }
            dismissSplash()
        }, SPLASH_MAX_HOLD_MS)
    }

    @Synchronized
    private fun dismissSplash() {
        if (splashDismissed) return
        splashDismissed = true
        try { prefetchWebView?.destroy() } catch (_: Exception) { }
        prefetchWebView = null
        startMainActivity()
    }

    private fun startMainActivity(animate: Boolean = true) {
        // Cancel infinite dot animators to prevent leaks
        dotAnimators.forEach { it.cancel() }
        dotAnimators.clear()

        startActivity(Intent(this, MainActivity::class.java))
        // Always use no-op transition: SplashActivity and MainActivity share the
        // same splash bg color during handoff, so any cross-activity animation
        // would expose an extra frame. Splash-off path was already animate=false.
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
        finish()
    }
}
