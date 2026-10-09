package com.web2native.app

import android.net.Uri
import android.util.Log
import org.json.JSONObject

/**
 * URL Allow/Block list (Add-on Pack v1, Feature 2).
 *
 * Parses the policy JSON shipped via BuildConfig.URL_POLICY_JSON and exposes
 * a single `shouldOpenExternally(url)` helper used by MainActivity's
 * WebViewClient. When the policy is `auto` (default) or unparseable, we
 * defer to the existing navigation logic — strictly additive behavior.
 *
 * Policy schema (matches the DB column `projects.url_policy`):
 *   {
 *     "mode": "auto" | "strict" | "block_list",
 *     "allow": ["example.com", "*.example.com"],
 *     "block": ["facebook.com"]
 *   }
 *
 *  - `auto`       → returns null (existing logic decides)
 *  - `strict`     → only hosts on `allow` open in-app; everything else external
 *  - `block_list` → everything in-app EXCEPT hosts on `block`
 */
object UrlPolicy {
    private const val TAG = "W2N_URL_POLICY"

    private data class Policy(
        val mode: String,
        val allow: List<String>,
        val block: List<String>,
    )

    private val policy: Policy by lazy { parse(BuildConfig.URL_POLICY_JSON) }

    /**
     * @return `true`  → open in Chrome Custom Tab / external browser
     *         `false` → load inside the WebView
     *         `null`  → no opinion (fall back to existing rules)
     */
    fun shouldOpenExternally(url: String?): Boolean? {
        if (url.isNullOrBlank()) return null
        val host = try { Uri.parse(url).host?.lowercase() } catch (_: Throwable) { null } ?: return null
        return when (policy.mode) {
            // Only return an opinion when the customer actually listed hosts.
            // `false`/no-match falls through (null) so the wrapper's normal
            // navigation + OAuth handling still runs for every other link.
            "strict"     -> if (policy.allow.none { it.isNotBlank() }) null
                            else if (matchesAny(host, policy.allow)) null else true
            "block_list" -> if (matchesAny(host, policy.block)) true else null
            else         -> null
        }
    }

    private fun matchesAny(host: String, patterns: List<String>): Boolean {
        for (raw in patterns) {
            val p = raw.trim().lowercase().removePrefix("https://").removePrefix("http://").trimEnd('/')
            if (p.isEmpty()) continue
            if (p.startsWith("*.")) {
                val suffix = p.substring(1) // ".example.com"
                if (host.endsWith(suffix) || host == suffix.trimStart('.')) return true
            } else if (host == p || host.endsWith(".$p")) {
                return true
            }
        }
        return false
    }

    private fun parse(json: String?): Policy {
        if (json.isNullOrBlank()) return Policy("auto", emptyList(), emptyList())
        return try {
            val obj = JSONObject(json)
            val mode = obj.optString("mode", "auto").ifBlank { "auto" }
            Policy(
                mode = if (mode in listOf("auto", "strict", "block_list")) mode else "auto",
                allow = obj.optJSONArray("allow")?.let { arr -> List(arr.length()) { arr.optString(it, "") } } ?: emptyList(),
                block = obj.optJSONArray("block")?.let { arr -> List(arr.length()) { arr.optString(it, "") } } ?: emptyList(),
            )
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to parse URL_POLICY_JSON, defaulting to auto", e)
            Policy("auto", emptyList(), emptyList())
        }
    }
}
