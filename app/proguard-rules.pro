# Default proguard rules
-keepattributes *Annotation*
# Keep class signatures + line numbers so any production crash report
# we receive via auth-debug-log has a readable stacktrace.
-keepattributes Signature, InnerClasses, EnclosingMethod, SourceFile, LineNumberTable

-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# Keep WebToNativeBridge JS interface class
-keepclassmembers class com.web2native.app.MainActivity$WebToNativeBridge {
    public *;
}
-keep class com.web2native.app.MainActivity$WebToNativeBridge { *; }

# Keep Firebase service for reflection-based registration
-keep class com.web2native.app.WebToNativeFirebaseService {
    public static void ensureTokenRegistered();
}

# Keep WebView JavaScript interfaces
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# Google Sign-In
-keep class com.google.android.gms.auth.** { *; }
-keep class androidx.credentials.** { *; }
-keep class com.google.android.libraries.identity.googleid.** { *; }
-keep class com.google.android.gms.common.** { *; }

# ─────────────────────────────────────────────────────────────────────────────
# Defense-in-depth: keep classes that R8 sometimes strips on release builds
# and that show up as the most common crash-on-open / "App not installed"
# root causes across the installed base.
# Each block is additive — removing it would only re-enable the original risk.
# ─────────────────────────────────────────────────────────────────────────────

# AdMob & Play Services Ads (reflection-heavy)
-keep class com.google.android.gms.ads.** { *; }
-dontwarn com.google.android.gms.ads.**

# Firebase Messaging (reflection on FirebaseMessagingService subclasses)
-keep class com.google.firebase.messaging.** { *; }
-keep class com.google.firebase.iid.** { *; }
-dontwarn com.google.firebase.**

# AndroidX Browser / Custom Tabs (used for OAuth bounce)
-keep class androidx.browser.** { *; }
-dontwarn androidx.browser.**

# AndroidX SplashScreen compat (keeps Theme.SplashScreen happy on API < 31)
-keep class androidx.core.splashscreen.** { *; }

# Material Components (theme attribute lookups via reflection on some OEMs)
-keep class com.google.android.material.** { *; }
-dontwarn com.google.android.material.**

# WebView client/chromeclient subclasses are referenced from native code
-keep class * extends android.webkit.WebViewClient { *; }
-keep class * extends android.webkit.WebChromeClient { *; }
-keep class * extends android.webkit.WebView { *; }

# Our own wrapper code — never strip anything here, the surface is small and
# every class is referenced indirectly (Application, services, lifecycle hooks).
-keep class com.web2native.app.** { *; }
