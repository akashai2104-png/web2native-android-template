package com.web2native.app

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.RelativeLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.browser.customtabs.CustomTabsIntent
import org.json.JSONObject

class TrialOverlayManager(private val activity: AppCompatActivity) {

    companion object {
        private const val TAG = "W2N_TRIAL"
        private const val PREFS_NAME = "web2native_prefs"
        private const val KEY_TRIAL_PHASE = "trial_phase"
        private const val KEY_TRIAL_LAST_CHECKED = "trial_last_checked"
        private const val KEY_TRIAL_BILLING_URL = "trial_billing_url"
        private const val KEY_TRIAL_HEADLINE = "trial_headline"
        private const val KEY_TRIAL_BODY = "trial_body"
        private const val KEY_TRIAL_CTA = "trial_cta_text"
        private const val KEY_TRIAL_DISMISS = "trial_dismiss_text"
        private const val KEY_TRIAL_HARD_BLOCK = "trial_hard_block"
        private const val KEY_TRIAL_DAYS_REMAINING = "trial_days_remaining"
        private const val KEY_TRIAL_OFF_CONFIRMED = "trial_off_confirmed"
        // Adaptive cache TTL — short when we're in hard-block so post-purchase unlock is near-instant.
        private const val CACHE_TTL_SOFT_MS = 60 * 60 * 1000L      // 1 hour
        private const val CACHE_TTL_HARD_MS = 30 * 1000L           // 30 seconds (re-check on each launch)
        private const val SOFT_BANNER_ID = 0x7F0E0099
        private const val FULLSCREEN_OVERLAY_ID = 0x7F0E009A
    }

    private val prefs = activity.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val handler = Handler(Looper.getMainLooper())
    private var softBannerDismissedThisSession = false

    fun checkTrialStatus() {
        val apiBaseUrl = try { BuildConfig.API_BASE_URL } catch (_: Exception) { "" }
        val projectId = try { BuildConfig.PROJECT_ID } catch (_: Exception) { "" }
        if (apiBaseUrl.isEmpty() || projectId.isEmpty()) return

        // If branding has been API-confirmed as HIDDEN (paid plan or active Launch Pass),
        // OR the trial endpoint has previously confirmed phase=off for paid reasons,
        // suppress the trial UI immediately and clear stale cache. This guarantees paid
        // users never see the soft banner / fullscreen overlay for even a millisecond,
        // regardless of what the trial cache held from their previous free state.
        val brandingApiConfirmed = prefs.getBoolean("branding_api_confirmed", false)
        val brandingVisible = prefs.getBoolean("branding_visible", false)
        val trialOffConfirmed = prefs.getBoolean(KEY_TRIAL_OFF_CONFIRMED, false)
        val paidConfirmed = (brandingApiConfirmed && !brandingVisible) || trialOffConfirmed
        if (paidConfirmed) {
            prefs.edit()
                .putString(KEY_TRIAL_PHASE, "off")
                .putBoolean(KEY_TRIAL_HARD_BLOCK, false)
                .putLong(KEY_TRIAL_LAST_CHECKED, System.currentTimeMillis())
                .apply()
            handler.post { applyPhase("off", false) }
            // Still hit the API once so the server-side cache stays fresh, but never render
            // a non-off phase locally for this user.
        } else {
            // Apply cached result immediately (only when we don't have a confirmed paid signal)
            val cachedPhase = prefs.getString(KEY_TRIAL_PHASE, "off") ?: "off"
            val cachedHardBlock = prefs.getBoolean(KEY_TRIAL_HARD_BLOCK, false)
            val lastChecked = prefs.getLong(KEY_TRIAL_LAST_CHECKED, 0)
            val ttl = if (cachedHardBlock) CACHE_TTL_HARD_MS else CACHE_TTL_SOFT_MS
            val cacheValid = System.currentTimeMillis() - lastChecked < ttl

            if (cacheValid && cachedPhase != "off") {
                handler.post { applyPhase(cachedPhase, cachedHardBlock) }
            }
        }
        val suppressLocally = paidConfirmed

        // Background fetch
        Thread {
            try {
                val url = java.net.URL("$apiBaseUrl/functions/v1/check-trial-status")
                val conn = url.openConnection() as java.net.HttpURLConnection
                conn.requestMethod = "POST"
                val anonKey = try { BuildConfig.SUPABASE_ANON_KEY } catch (_: Exception) { "" }
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("apikey", anonKey)
                conn.setRequestProperty("Authorization", "Bearer $anonKey")
                conn.connectTimeout = 8000
                conn.readTimeout = 8000
                conn.doOutput = true
                val body = """{"project_id":"$projectId"}"""
                conn.outputStream.use { it.write(body.toByteArray()) }
                val code = conn.responseCode
                if (code in 200..299) {
                    val response = conn.inputStream.bufferedReader().use { it.readText() }
                    val json = JSONObject(response)
                    val phase = json.optString("phase", "off")
                    val billingUrl = json.optString("billing_url", "")
                    val headline = json.optString("headline", "")
                    val bodyText = json.optString("body", "")
                    val ctaText = json.optString("cta_text", "Upgrade Now")
                    val dismissText = json.optString("dismiss_text", "Continue with branding")
                    val hardBlock = json.optBoolean("hard_block", false)
                    val daysRemaining = json.optInt("days_remaining", -1)

                    val isPaid = json.optBoolean("paid", false)
                    val offConfirmed = phase == "off" && isPaid

                    prefs.edit()
                        .putString(KEY_TRIAL_PHASE, phase)
                        .putLong(KEY_TRIAL_LAST_CHECKED, System.currentTimeMillis())
                        .putString(KEY_TRIAL_BILLING_URL, billingUrl)
                        .putString(KEY_TRIAL_HEADLINE, headline)
                        .putString(KEY_TRIAL_BODY, bodyText)
                        .putString(KEY_TRIAL_CTA, ctaText)
                        .putString(KEY_TRIAL_DISMISS, dismissText)
                        .putBoolean(KEY_TRIAL_HARD_BLOCK, hardBlock)
                        .putInt(KEY_TRIAL_DAYS_REMAINING, daysRemaining)
                        .putBoolean(KEY_TRIAL_OFF_CONFIRMED, if (offConfirmed) true else if (phase != "off") false else trialOffConfirmed)
                        .apply()

                    Log.d(TAG, "Trial status: phase=$phase hard=$hardBlock days=$daysRemaining paid=$isPaid suppressLocally=$suppressLocally")
                    // If branding is API-confirmed hidden (paid/Launch Pass), force-off
                    // regardless of what the trial endpoint returned. Defense-in-depth.
                    val effectivePhase = if (suppressLocally) "off" else phase
                    val effectiveHardBlock = if (suppressLocally) false else hardBlock
                    handler.post { applyPhase(effectivePhase, effectiveHardBlock) }
                } else {
                    Log.w(TAG, "Trial check failed: HTTP $code")
                }
                conn.disconnect()
            } catch (e: Exception) {
                Log.w(TAG, "Trial check error", e)
                // Fail-open: do nothing
            }
        }.start()
    }

    private fun applyPhase(phase: String, hardBlock: Boolean = false) {
        when (phase) {
            "soft" -> {
                removeFullscreenOverlay()
                if (!softBannerDismissedThisSession) {
                    showSoftBanner()
                }
            }
            "fullscreen" -> {
                showFullscreenOverlay(hardBlock)
            }
            "off" -> {
                removeSoftBanner()
                removeFullscreenOverlay()
            }
        }
    }

    private fun dpToPx(dp: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, dp.toFloat(),
            activity.resources.displayMetrics
        ).toInt()
    }

    // ==================== SOFT BANNER ====================

    private fun showSoftBanner() {
        val rootContent = activity.findViewById<RelativeLayout>(R.id.rootContent) ?: return
        if (rootContent.findViewById<View>(SOFT_BANNER_ID) != null) return

        val banner = LinearLayout(activity).apply {
            id = SOFT_BANNER_ID
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#E6000000")) // semi-transparent dark
            setPadding(dpToPx(12), dpToPx(10), dpToPx(8), dpToPx(10))
            isClickable = true
            isFocusable = true
        }

        val textView = TextView(activity).apply {
            text = prefs.getString(KEY_TRIAL_BODY, "Free preview — Upgrade to remove branding and publish to Play Store → Tap here to view plans")
            setTextColor(Color.parseColor("#7CB9FF"))
            paintFlags = paintFlags or android.graphics.Paint.UNDERLINE_TEXT_FLAG
            textSize = 12f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        val closeBtn = TextView(activity).apply {
            text = "✕"
            setTextColor(Color.parseColor("#AAAAAA"))
            textSize = 16f
            setPadding(dpToPx(12), 0, dpToPx(4), 0)
            setOnClickListener {
                softBannerDismissedThisSession = true
                removeSoftBanner()
            }
        }

        banner.addView(textView)
        banner.addView(closeBtn)

        banner.setOnClickListener { openBillingUrl() }

        val params = RelativeLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        // Place below toolbar/progressBar
        params.addRule(RelativeLayout.BELOW, R.id.progressBar)

        rootContent.addView(banner, params)

        // Adjust webView to sit below the banner
        val webView = activity.findViewById<View>(R.id.webView)
        val webParams = webView?.layoutParams as? RelativeLayout.LayoutParams
        webParams?.addRule(RelativeLayout.BELOW, SOFT_BANNER_ID)
        webView?.layoutParams = webParams

        Log.d(TAG, "Soft banner shown")
    }

    private fun removeSoftBanner() {
        val rootContent = activity.findViewById<RelativeLayout>(R.id.rootContent) ?: return
        val banner = rootContent.findViewById<View>(SOFT_BANNER_ID)
        if (banner != null) {
            rootContent.removeView(banner)
            // Restore webView below progressBar
            val webView = activity.findViewById<View>(R.id.webView)
            val webParams = webView?.layoutParams as? RelativeLayout.LayoutParams
            webParams?.addRule(RelativeLayout.BELOW, R.id.progressBar)
            webView?.layoutParams = webParams
            Log.d(TAG, "Soft banner removed")
        }
    }

    // ==================== FULLSCREEN OVERLAY ====================

    private fun showFullscreenOverlay(hardBlock: Boolean = false) {
        val rootContent = activity.findViewById<RelativeLayout>(R.id.rootContent) ?: return
        if (rootContent.findViewById<View>(FULLSCREEN_OVERLAY_ID) != null) return

        val overlay = FrameLayout(activity).apply {
            id = FULLSCREEN_OVERLAY_ID
            isClickable = true
            isFocusable = true
            isFocusableInTouchMode = true
            val gradient = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.parseColor("#1A1A2E"), Color.parseColor("#16213E"))
            )
            background = gradient
            // When hard-blocked, swallow the BACK key so the user can't escape the paywall.
            if (hardBlock) {
                setOnKeyListener { _, keyCode, event ->
                    if (keyCode == android.view.KeyEvent.KEYCODE_BACK && event.action == android.view.KeyEvent.ACTION_DOWN) {
                        true
                    } else false
                }
                requestFocus()
            }
        }

        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dpToPx(32), dpToPx(48), dpToPx(32), dpToPx(48))
        }

        val headline = prefs.getString(KEY_TRIAL_HEADLINE, "⏰ Your Free Preview Has Expired") ?: ""
        val bodyText = prefs.getString(KEY_TRIAL_BODY,
            "87% of app creators who upgrade within 24 hours launch on Play Store the same week. Don't let your app sit unfinished — your competitors won't wait.") ?: ""
        val ctaText = prefs.getString(KEY_TRIAL_CTA, "🚀 Upgrade Now — Remove Branding & Go Live") ?: ""
        val dismissText = prefs.getString(KEY_TRIAL_DISMISS, "Continue with branding (limited features)") ?: ""

        // Headline
        content.addView(TextView(activity).apply {
            text = headline
            setTextColor(Color.WHITE)
            textSize = 24f
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dpToPx(20))
        })

        // Body
        content.addView(TextView(activity).apply {
            text = bodyText
            setTextColor(Color.parseColor("#CCCCCC"))
            textSize = 15f
            gravity = Gravity.CENTER
            setLineSpacing(dpToPx(4).toFloat(), 1f)
            setPadding(0, 0, 0, dpToPx(32))
        })

        // CTA Button
        val ctaButton = TextView(activity).apply {
            text = ctaText
            setTextColor(Color.WHITE)
            textSize = 16f
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(dpToPx(24), dpToPx(16), dpToPx(24), dpToPx(16))
            val btnBg = GradientDrawable().apply {
                cornerRadius = dpToPx(12).toFloat()
                setColor(Color.parseColor("#1D4ED8"))
            }
            background = btnBg
            setOnClickListener { openBillingUrl() }
        }
        val ctaParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        ctaParams.bottomMargin = dpToPx(12)
        content.addView(ctaButton, ctaParams)


        // Dismiss link — only when NOT hard-blocked
        if (!hardBlock) {
            content.addView(TextView(activity).apply {
                text = dismissText
                setTextColor(Color.parseColor("#888888"))
                textSize = 13f
                gravity = Gravity.CENTER
                setPadding(0, dpToPx(8), 0, 0)
                setOnClickListener {
                    removeFullscreenOverlay()
                    softBannerDismissedThisSession = false
                    showSoftBanner()
                }
            })
        }

        val contentParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { gravity = Gravity.CENTER }

        overlay.addView(content, contentParams)

        val overlayParams = RelativeLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        rootContent.addView(overlay, overlayParams)

        // Bring to front so it covers webview
        overlay.bringToFront()
        Log.d(TAG, "Fullscreen overlay shown")
    }

    private fun removeFullscreenOverlay() {
        val rootContent = activity.findViewById<RelativeLayout>(R.id.rootContent) ?: return
        val overlay = rootContent.findViewById<View>(FULLSCREEN_OVERLAY_ID)
        if (overlay != null) {
            rootContent.removeView(overlay)
            Log.d(TAG, "Fullscreen overlay removed")
        }
    }

    // ==================== HELPERS ====================

    private fun openBillingUrl() {
        val url = prefs.getString(KEY_TRIAL_BILLING_URL, "https://nativeappai.com/billing") ?: "https://nativeappai.com/billing"
        try {
            val intent = CustomTabsIntent.Builder()
                .setShowTitle(true)
                .build()
            intent.launchUrl(activity, Uri.parse(url))
        } catch (e: Exception) {
            Log.w(TAG, "Could not open billing URL", e)
            try {
                val browserIntent = android.content.Intent(android.content.Intent.ACTION_VIEW, Uri.parse(url))
                activity.startActivity(browserIntent)
            } catch (e2: Exception) {
                Log.e(TAG, "Could not open billing URL in any browser", e2)
            }
        }
    }
}
