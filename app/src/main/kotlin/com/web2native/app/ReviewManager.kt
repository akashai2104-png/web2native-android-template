package com.web2native.app

import android.app.Activity
import android.content.Context
import android.util.Log
import com.google.android.play.core.review.ReviewManagerFactory

/**
 * In-App Review prompt (Add-on Pack v1, Feature 1).
 *
 * Counts launches in SharedPreferences. Once the user has opened the app
 * `minSessions` times (and we haven't already shown them the card for this
 * VERSION_CODE), we ask Google Play to display its native rating sheet.
 *
 * Strictly additive — disabling the feature (CM_REVIEW_ENABLED=false) makes
 * every call here a fast no-op.
 */
object ReviewManager {
    private const val PREFS = "w2n_review"
    private const val KEY_SESSIONS = "session_count"
    private const val KEY_SHOWN_FOR = "shown_for_version"
    private const val TAG = "W2N_REVIEW"

    /**
     * Call from MainActivity.onResume(). Increments session counter on the
     * first call per process and, when the threshold is reached, requests
     * the native review card. All Play Core errors are swallowed — review
     * is a best-effort polish feature and must never crash the app.
     */
    fun maybeShow(activity: Activity) {
        try {
            if (!BuildConfig.REVIEW_ENABLED) return
            val minSessions = BuildConfig.REVIEW_MIN_SESSIONS.coerceAtLeast(1)
            val currentVersion = BuildConfig.VERSION_CODE_INT
            val prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

            val shownFor = prefs.getInt(KEY_SHOWN_FOR, -1)
            if (shownFor == currentVersion) return  // already shown this version

            // Only increment once per cold process to avoid counting onResume
            // bouncing from Custom Tabs / OAuth as separate sessions.
            if (!sessionCountedThisProcess) {
                sessionCountedThisProcess = true
                val newCount = prefs.getInt(KEY_SESSIONS, 0) + 1
                prefs.edit().putInt(KEY_SESSIONS, newCount).apply()
                Log.d(TAG, "session count incremented to $newCount (threshold=$minSessions)")
                if (newCount < minSessions) return
            } else if (prefs.getInt(KEY_SESSIONS, 0) < minSessions) {
                return
            }

            val manager = ReviewManagerFactory.create(activity)
            manager.requestReviewFlow().addOnCompleteListener { req ->
                if (!req.isSuccessful) {
                    Log.w(TAG, "requestReviewFlow failed", req.exception)
                    return@addOnCompleteListener
                }
                try {
                    manager.launchReviewFlow(activity, req.result).addOnCompleteListener {
                        prefs.edit().putInt(KEY_SHOWN_FOR, currentVersion).apply()
                        Log.d(TAG, "review flow finished for version $currentVersion")
                    }
                } catch (e: Throwable) {
                    Log.w(TAG, "launchReviewFlow threw", e)
                }
            }
        } catch (e: Throwable) {
            Log.w(TAG, "maybeShow swallowed throwable", e)
        }
    }

    @Volatile private var sessionCountedThisProcess = false
}
