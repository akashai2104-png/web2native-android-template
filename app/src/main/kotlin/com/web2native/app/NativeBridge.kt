package com.web2native.app

import android.content.Context
import android.content.Intent
import android.util.Log
import android.webkit.JavascriptInterface
import org.json.JSONObject

/**
 * Native JS bridge (Add-on Pack v1, Feature 3 — zero-config Option B).
 *
 * Exposed to the wrapped web app as `window.NativeApp.share({title,text,url})`.
 * MainActivity also injects a tiny shim on every onPageFinished that
 * auto-upgrades `navigator.share()` to use this bridge — so creators get
 * native share with **zero changes** to their website code.
 */
class NativeBridge(private val context: Context) {

    @JavascriptInterface
    fun share(json: String?) {
        try {
            val obj = if (json.isNullOrBlank()) JSONObject() else JSONObject(json)
            val title = obj.optString("title", "")
            val text = obj.optString("text", "")
            val url = obj.optString("url", "")

            val body = listOf(text, url).filter { it.isNotBlank() }.joinToString("\n").ifBlank { title }
            if (body.isBlank()) {
                Log.d(TAG, "share() called with empty payload — ignoring")
                return
            }

            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, body)
                if (title.isNotBlank()) putExtra(Intent.EXTRA_SUBJECT, title)
            }
            val chooser = Intent.createChooser(intent, null).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(chooser)
        } catch (e: Throwable) {
            Log.w(TAG, "share() threw — swallowed", e)
        }
    }

    companion object {
        const val NAME = "NativeApp"
        private const val TAG = "W2N_BRIDGE"

        /**
         * JS shim evaluated on every onPageFinished. Patches navigator.share
         * to route through the native bridge when both exist. Safe no-op
         * when either is missing.
         */
        const val SHIM_JS = """
            (function(){
              try {
                if (window.__W2N_SHARE_PATCHED__) return;
                if (!window.NativeApp || typeof window.NativeApp.share !== 'function') return;
                window.__W2N_SHARE_PATCHED__ = true;
                var orig = (typeof navigator.share === 'function') ? navigator.share.bind(navigator) : null;
                navigator.share = function(data){
                  try {
                    var payload = JSON.stringify(data || {});
                    window.NativeApp.share(payload);
                    return Promise.resolve();
                  } catch (e) {
                    if (orig) return orig(data);
                    return Promise.reject(e);
                  }
                };
              } catch (e) { /* swallow — never break the page */ }
            })();
        """
    }
}
