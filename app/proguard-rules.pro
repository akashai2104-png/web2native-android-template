# Default proguard rules
-keepattributes *Annotation*
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
