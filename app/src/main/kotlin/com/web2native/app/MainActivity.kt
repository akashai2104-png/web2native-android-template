package com.web2native.app

import android.annotation.SuppressLint
import android.app.Activity
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
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
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.content.FileProvider
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.gms.ads.*
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var progressBar: ProgressBar
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private var loadingOverlay: LinearLayout? = null
    private var pageLoaded = false

    // File upload support
    private var fileUploadCallback: ValueCallback<Array<Uri>>? = null
    private var cameraPhotoPath: String? = null
    private lateinit var fileChooserLauncher: ActivityResultLauncher<Intent>

    // AdMob
    private var interstitialAd: InterstitialAd? = null
    private val admobEnabled: Boolean
        get() = BuildConfig.ADMOB_APP_ID.isNotEmpty() && BuildConfig.ADMOB_APP_ID != "NONE"

    // Google OAuth hosts that must open in Custom Tabs (not WebView)
    private val customTabHosts = listOf(
        "accounts.google.com",
        "oauth2.googleapis.com",
    )

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
                val showAppBar = BuildConfig.SHOW_APP_BAR
                val splashAnim = BuildConfig.SPLASH_ANIMATION
                val splashDesign = try { BuildConfig.SPLASH_DESIGN } catch (_: Exception) { "dots" }
                val body = """{"project_id":"$projectId","platform":"android","config_receipt":{"show_app_bar":$showAppBar,"splash_animation":"$splashAnim","splash_design":"$splashDesign","package_name":"${BuildConfig.APPLICATION_ID}","version":"${BuildConfig.VERSION_NAME}"}}"""
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

    /**
     * Opens a URL in Chrome Custom Tabs (secure browser context).
     * Google OAuth requires this — WebView is blocked by policy.
     */
    private fun openInCustomTab(url: String) {
        try {
            val customTabsIntent = CustomTabsIntent.Builder()
                .setShowTitle(true)
                .build()
            customTabsIntent.launchUrl(this, Uri.parse(url))
        } catch (e: Exception) {
            // Fallback to system browser if Custom Tabs unavailable
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }
    }

    /**
     * Check if a URL belongs to a provider that blocks WebView login
     * (e.g., Google's disallowed_useragent policy).
     */
    private fun requiresCustomTab(host: String): Boolean {
        return customTabHosts.any { host == it || host.endsWith(".$it") }
    }

    /**
     * Detect Google OAuth start URL early (before redirect to accounts.google.com)
     * so the full auth chain runs in Custom Tabs instead of WebView.
     */
    private fun isGoogleOAuthStartUrl(url: String): Boolean {
        return url.contains("/auth/v1/authorize") &&
            (url.contains("provider=google") || url.contains("provider%3Dgoogle"))
    }


    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // === STAGE 7: Runtime BuildConfig verification ===
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

        // Apply dynamic theme color to status bar
        val themeColor = try {
            Color.parseColor(BuildConfig.THEME_COLOR)
        } catch (e: Exception) {
            Color.parseColor("#1D4ED8")
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
        window.statusBarColor = themeColor

        // Apply orientation lock
        requestedOrientation = when (BuildConfig.ORIENTATION) {
            "portrait" -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            "landscape" -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            else -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }

        // Apply full-screen / immersive mode
        if (BuildConfig.FULL_SCREEN) {
            Log.i("APP_CONFIG", "Applying FULL_SCREEN mode")
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

        // Handle app bar visibility
        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        val appBarTitle = findViewById<TextView>(R.id.appBarTitle)

        webView = findViewById(R.id.webView)
        progressBar = findViewById(R.id.progressBar)
        swipeRefresh = findViewById(R.id.swipeRefresh)
        loadingOverlay = findViewById(R.id.loadingOverlay)

        val debugConfigText = findViewById<TextView>(R.id.debugConfigText)
        debugConfigText.text = "SHOW_APP_BAR = ${BuildConfig.SHOW_APP_BAR}\n" +
            "FULL_SCREEN = ${BuildConfig.FULL_SCREEN}\n" +
            "SPLASH_ANIMATION = ${BuildConfig.SPLASH_ANIMATION}\n" +
            "PUSH_ENABLED = ${BuildConfig.PUSH_ENABLED}"
        debugConfigText.visibility = View.VISIBLE
        Log.i("APP_CONFIG", "Runtime debug panel => " + debugConfigText.text.toString().replace("\n", " | "))

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

            // Ensure no top gap remains when toolbar is hidden
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

        // Avoid a second spinner after splash; let splash own initial loading experience
        if (splashActive) {
            loadingOverlay?.visibility = View.GONE
            webView.visibility = View.VISIBLE
            Log.i("APP_CONFIG", "Splash active — loading overlay hidden")
        } else {
            loadingOverlay?.setBackgroundColor(Color.WHITE)
            Log.i("APP_CONFIG", "No splash — loading overlay shown")
        }

        Log.i("APP_CONFIG", "splashActive=$splashActive, splashDesign=$splashDesign")

        // Cookie & session persistence
        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(webView, true)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            allowFileAccess = true
            allowContentAccess = true
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE

            // Custom User-Agent for website traffic detection
            val defaultUA = userAgentString
            userAgentString = "$defaultUA WebToNative/1.0"

            // Offline caching strategy
            databaseEnabled = true
            cacheMode = if (isNetworkAvailable()) {
                WebSettings.LOAD_DEFAULT
            } else {
                WebSettings.LOAD_CACHE_ELSE_NETWORK
            }
        }

        // JavaScript bridge — allows websites to detect native app
        webView.addJavascriptInterface(WebToNativeBridge(), "WebToNative")

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
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            }
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url?.toString() ?: return false
                val uri = Uri.parse(url)
                val host = uri.host?.lowercase() ?: ""
                val baseHost = Uri.parse(BuildConfig.WEBSITE_URL).host

                // Google OAuth must stay fully in a secure browser context.
                // Intercept both the provider start URL and Google hosts.
                if (isGoogleOAuthStartUrl(url) || requiresCustomTab(host)) {
                    openInCustomTab(url)
                    return true
                }

                // Allow other OAuth/auth provider URLs inside WebView
                val oauthHosts = listOf(
                    "appleid.apple.com",
                    "www.facebook.com",
                    "github.com",
                    "login.microsoftonline.com",
                    "discord.com",
                    "supabase.co",
                    "firebaseapp.com",
                    "auth0.com",
                    "clerk.dev",
                    "clerk.accounts.dev",
                    "login.live.com",
                    "okta.com",
                    "cognito-idp.amazonaws.com",
                    "amazoncognito.com",
                )
                val isOAuthUrl = oauthHosts.any { host == it || host.endsWith(".$it") }

                // Also keep auth-related redirects (with redirect_uri/callback params) in WebView
                val isAuthRedirect = url.contains("redirect_uri=") || url.contains("callback=") || url.contains("/auth/") || url.contains("/oauth/")

                return if (isSameDomain(host, baseHost) || isOAuthUrl || isAuthRedirect) {
                    false
                } else {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                    true
                }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                CookieManager.getInstance().flush()

                // Reveal WebView and fade out loading overlay
                if (!pageLoaded) {
                    pageLoaded = true
                    webView.visibility = View.VISIBLE
                    loadingOverlay?.let { overlay ->
                        val fadeOut = AlphaAnimation(1f, 0f).apply { duration = 300; fillAfter = true }
                        overlay.startAnimation(fadeOut)
                        overlay.postDelayed({ overlay.visibility = View.GONE }, 300)
                    }
                }
            }

            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                if (request?.isForMainFrame == true && !isNetworkAvailable()) {
                    view?.loadUrl("file:///android_asset/offline.html")
                }
            }

            override fun onReceivedHttpError(view: WebView?, request: WebResourceRequest?, errorResponse: WebResourceResponse?) {
                if (request?.isForMainFrame == true && !isNetworkAvailable()) {
                    view?.loadUrl("file:///android_asset/offline.html")
                }
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                if (newProgress < 100) {
                    progressBar.visibility = View.VISIBLE
                    progressBar.progress = newProgress
                } else {
                    progressBar.visibility = View.GONE
                }
            }

            // Camera & file upload support
            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                fileUploadCallback?.onReceiveValue(null)
                fileUploadCallback = filePathCallback

                val takePictureIntent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
                if (takePictureIntent.resolveActivity(packageManager) != null) {
                    try {
                        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                        val photoFile = File.createTempFile("IMG_${timeStamp}_", ".jpg", cacheDir)
                        cameraPhotoPath = "file:${photoFile.absolutePath}"
                        val photoUri = FileProvider.getUriForFile(this@MainActivity, "${packageName}.fileprovider", photoFile)
                        takePictureIntent.putExtra(MediaStore.EXTRA_OUTPUT, photoUri)
                    } catch (ex: Exception) {
                        cameraPhotoPath = null
                    }
                }

                val contentSelectionIntent = Intent(Intent.ACTION_GET_CONTENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = fileChooserParams?.acceptTypes?.firstOrNull()?.takeIf { it.isNotEmpty() } ?: "*/*"
                }

                val chooserIntent = Intent(Intent.ACTION_CHOOSER).apply {
                    putExtra(Intent.EXTRA_INTENT, contentSelectionIntent)
                    putExtra(Intent.EXTRA_TITLE, "Choose file")
                    if (cameraPhotoPath != null) {
                        putExtra(Intent.EXTRA_INITIAL_INTENTS, arrayOf(takePictureIntent))
                    }
                }

                fileChooserLauncher.launch(chooserIntent)
                return true
            }
        }

        // Only allow pull-to-refresh when WebView is scrolled to the top
        webView.setOnScrollChangeListener { _, _, scrollY, _, _ ->
            swipeRefresh.isEnabled = scrollY == 0
        }

        swipeRefresh.setOnRefreshListener {
            webView.settings.cacheMode = if (isNetworkAvailable()) {
                WebSettings.LOAD_DEFAULT
            } else {
                WebSettings.LOAD_CACHE_ELSE_NETWORK
            }
            webView.reload()
            swipeRefresh.isRefreshing = false
        }

        // Handle deep link intents
        val deepLinkUrl = intent?.data?.toString()
        if (deepLinkUrl != null) {
            webView.loadUrl(deepLinkUrl)
        } else {
            webView.loadUrl(BuildConfig.WEBSITE_URL)
        }

        maybeRegisterPushToken()
        trackInstallOnce()

        // Initialize AdMob if configured
        if (admobEnabled) {
            initAdMob()
        }
    }

    private fun initAdMob() {
        MobileAds.initialize(this) { initStatus ->
            Log.d("W2N_ADMOB", "AdMob initialized: $initStatus")
        }

        // Load banner ad if unit ID is provided
        if (BuildConfig.ADMOB_BANNER_ID.isNotEmpty()) {
            val adView = AdView(this)
            adView.setAdSize(AdSize.BANNER)
            adView.adUnitId = BuildConfig.ADMOB_BANNER_ID

            val bannerContainer = findViewById<FrameLayout>(R.id.bannerAdContainer)
            bannerContainer?.let {
                it.visibility = View.VISIBLE
                it.addView(adView)
                adView.loadAd(AdRequest.Builder().build())
            }
        }

        // Load interstitial ad if unit ID is provided
        if (BuildConfig.ADMOB_INTERSTITIAL_ID.isNotEmpty()) {
            loadInterstitialAd()
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

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        intent?.data?.toString()?.let { url ->
            webView.loadUrl(url)
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK && webView.canGoBack()) {
            webView.goBack()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
    }

    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        super.onRestoreInstanceState(savedInstanceState)
        webView.restoreState(savedInstanceState)
    }

    // JavaScript bridge class
    class WebToNativeBridge {
        @JavascriptInterface
        fun isNativeApp(): Boolean = true

        @JavascriptInterface
        fun getAppVersion(): String = BuildConfig.VERSION_NAME

        @JavascriptInterface
        fun getAppPackage(): String = BuildConfig.APPLICATION_ID
    }
}
