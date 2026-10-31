# Keep JavaScript bridge methods called from WebView pages.
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
