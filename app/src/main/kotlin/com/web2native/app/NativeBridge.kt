package com.web2native.app

import android.content.Context
import android.content.Intent
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File

/**
 * Native JS bridge (Add-on Pack v1, Feature 3 — zero-config Option B).
 *
 * Exposed to the wrapped web app as `window.NativeApp.share({title,text,url})`.
 * MainActivity also injects a tiny shim on every onPageFinished that
 * auto-upgrades `navigator.share()` to use this bridge — so creators get
 * native share with **zero changes** to their website code.
 *
 * Files passed to navigator.share({files}) are sent as base64 to
 * shareFiles() and shared through the phone's share sheet.
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

    /**
     * Shares files from navigator.share({files}). Payload:
     * {title,text,url,files:[{name,type,data(base64)}]}. Returns false on any
     * failure so the page shim can fall back to saving the file.
     */
    @JavascriptInterface
    fun shareFiles(json: String?): Boolean {
        return try {
            val obj = JSONObject(json ?: "{}")
            val arr = obj.optJSONArray("files") ?: return false
            val dir = File(context.cacheDir, "w2n_share").apply { mkdirs() }
            try { dir.listFiles()?.forEach { it.delete() } } catch (_: Throwable) { }
            val uris = ArrayList<android.net.Uri>()
            val types = HashSet<String>()
            for (i in 0 until arr.length()) {
                val f = arr.getJSONObject(i)
                val mime = f.optString("type", "").ifBlank { "application/octet-stream" }
                var name = f.optString("name", "").replace(Regex("[\\\\/:*?\"<>|]"), "_").ifBlank { "file_$i" }
                if (!name.contains('.')) {
                    MimeTypeMap.getSingleton().getExtensionFromMimeType(mime)?.let { name = "$name.$it" }
                }
                val data = f.optString("data", "")
                if (data.isEmpty()) return false
                val out = File(dir, name)
                out.writeBytes(android.util.Base64.decode(data, android.util.Base64.DEFAULT))
                uris.add(FileProvider.getUriForFile(context, context.packageName + ".fileprovider", out))
                types.add(mime)
            }
            if (uris.isEmpty()) return false
            val mimeAll = if (types.size == 1) types.first() else "*/*"
            val text = listOf(obj.optString("text", ""), obj.optString("url", "")).filter { it.isNotBlank() }.joinToString("\n")
            val title = obj.optString("title", "")
            val intent = (if (uris.size == 1) {
                Intent(Intent.ACTION_SEND).apply { putExtra(Intent.EXTRA_STREAM, uris[0]) }
            } else {
                Intent(Intent.ACTION_SEND_MULTIPLE).apply { putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris) }
            }).apply {
                type = mimeAll
                if (text.isNotBlank()) putExtra(Intent.EXTRA_TEXT, text)
                if (title.isNotBlank()) putExtra(Intent.EXTRA_SUBJECT, title)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val chooser = Intent.createChooser(intent, null).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(chooser)
            true
        } catch (e: Throwable) {
            Log.w(TAG, "shareFiles() failed", e)
            false
        }
    }

    companion object {
        const val NAME = "NativeApp"
        private const val TAG = "W2N_BRIDGE"

        /**
         * JS shim evaluated on every onPageFinished. Patches navigator.share
         * to route through the native bridge when both exist. Safe no-op
         * when either is missing. Text shares behave exactly as before;
         * shares with files go to shareFiles(), falling back to saving the
         * file to Downloads if sharing fails.
         */
        const val SHIM_JS = """
            (function(){
              try {
                if (window.__W2N_SHARE_PATCHED__) return;
                if (!window.NativeApp || typeof window.NativeApp.share !== 'function') return;
                window.__W2N_SHARE_PATCHED__ = true;
                var orig = (typeof navigator.share === 'function') ? navigator.share.bind(navigator) : null;
                function readFile(f){
                  return new Promise(function(res, rej){
                    try {
                      var fr = new FileReader();
                      fr.onloadend = function(){
                        var s = String(fr.result || ''); var i = s.indexOf(',');
                        res({ name: f.name || '', type: f.type || '', data: i >= 0 ? s.substring(i + 1) : s });
                      };
                      fr.onerror = function(){ rej(fr.error); };
                      fr.readAsDataURL(f);
                    } catch (e) { rej(e); }
                  });
                }
                function saveAll(list){
                  try {
                    if (!window.WebToNative || typeof window.WebToNative.saveBase64File !== 'function') return false;
                    var ok = false;
                    for (var i = 0; i < list.length; i++) { ok = window.WebToNative.saveBase64File(list[i].name, list[i].type, list[i].data) || ok; }
                    return ok;
                  } catch (e) { return false; }
                }
                navigator.share = function(data){
                  try {
                    var files = (data && data.files) ? Array.prototype.slice.call(data.files) : [];
                    if (files.length && typeof window.NativeApp.shareFiles === 'function') {
                      return Promise.all(files.map(readFile)).then(function(list){
                        var payload = JSON.stringify({ title: data.title || '', text: data.text || '', url: data.url || '', files: list });
                        var shared = false;
                        try { shared = window.NativeApp.shareFiles(payload); } catch (e) { shared = false; }
                        if (!shared && !saveAll(list)) throw new Error('share failed');
                      });
                    }
                    var payload = JSON.stringify(data || {});
                    window.NativeApp.share(payload);
                    return Promise.resolve();
                  } catch (e) {
                    if (orig) return orig(data);
                    return Promise.reject(e);
                  }
                };
                if (typeof window.NativeApp.shareFiles === 'function') {
                  var origCan = (typeof navigator.canShare === 'function') ? navigator.canShare.bind(navigator) : null;
                  navigator.canShare = function(data){
                    try {
                      if (data && data.files && data.files.length) return true;
                      return origCan ? origCan(data) : true;
                    } catch (e) { return true; }
                  };
                }
              } catch (e) { /* swallow — never break the page */ }
            })();
        """
    }
}
