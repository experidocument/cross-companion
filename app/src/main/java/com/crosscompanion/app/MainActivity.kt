package com.crosscompanion.app

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Bundle
import android.os.Message
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.SystemBarStyle
import androidx.activity.addCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import com.crosscompanion.app.databinding.ActivityMainBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private val prefs by lazy { Prefs(this) }
    private val homeUrl by lazy { getString(R.string.home_url) }

    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private val fileChooser = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        filePathCallback?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data))
        filePathCallback = null
    }

    private var lastBackPress = 0L
    private var mainFrameFailed = false
    private var googleDialog: AlertDialog? = null

    /** Updated from page JS on every touchstart; lets pull-to-refresh respect inner scrollers. */
    @Volatile private var pageCanScrollUp = false

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            runOnUiThread { if (b.offlineView.visibility == View.VISIBLE) retry() }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        applyInsets()
        setUpWebView()
        setUpRefresh()
        setUpBackHandling()

        b.retryButton.setOnClickListener { retry() }
        b.helpFab.setOnClickListener { showOnboarding() }
        b.hintFab.setOnClickListener { showCompanion() }

        if (savedInstanceState != null) {
            b.webView.restoreState(savedInstanceState)
        } else {
            val resume = prefs.lastUrl?.takeIf { isGameUrl(it) }
            b.webView.loadUrl(resume ?: homeUrl)
        }
        if (!isOnline()) showOffline()

        if (!prefs.onboardingSeen) b.root.post { showOnboarding() }
    }

    override fun onStart() {
        super.onStart()
        getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(networkCallback)
    }

    override fun onStop() {
        super.onStop()
        runCatching { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(networkCallback) }
        CookieManager.getInstance().flush()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        b.webView.saveState(outState)
    }

    override fun onDestroy() {
        b.webView.destroy()
        super.onDestroy()
    }

    // region Layout

    private fun applyInsets() {
        val fabMargin = resources.getDimensionPixelSize(R.dimen.fab_margin)
        ViewCompat.setOnApplyWindowInsetsListener(b.root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            val bottom = maxOf(bars.bottom, ime.bottom)
            b.swipeRefresh.updatePadding(left = bars.left, top = bars.top, right = bars.right, bottom = bottom)
            b.offlineView.updatePadding(top = bars.top, bottom = bars.bottom)
            b.statusScrim.updateLayoutParams<ViewGroup.LayoutParams> { height = bars.top }
            b.navScrim.updateLayoutParams<ViewGroup.LayoutParams> { height = bottom }
            b.pageProgress.updateLayoutParams<ViewGroup.MarginLayoutParams> { topMargin = bars.top }
            b.helpFab.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                leftMargin = bars.left + fabMargin
                bottomMargin = bottom + fabMargin
            }
            b.hintFab.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                rightMargin = bars.right + fabMargin
                bottomMargin = bottom + fabMargin
            }
            // With the keyboard up the grid is squeezed and the buttons would cover cells.
            val imeVisible = insets.isVisible(WindowInsetsCompat.Type.ime())
            listOf(b.helpFab, b.hintFab).forEach { if (imeVisible) it.hide() else it.show() }
            WindowInsetsCompat.CONSUMED
        }
    }

    // endregion

    // region WebView

    @SuppressLint("SetJavaScriptEnabled")
    private fun setUpWebView() {
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
        val web = b.webView
        web.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        with(web.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = true
            mediaPlaybackRequiresUserGesture = true
            allowFileAccess = false
        }
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(web, true)
        }
        web.addJavascriptInterface(ShellBridge(), "CompanionShell")
        web.webViewClient = ShellClient()
        web.webChromeClient = ShellChromeClient()
    }

    private inner class ShellBridge {
        @JavascriptInterface
        fun setCanScrollUp(value: Boolean) {
            pageCanScrollUp = value
        }

        /** Receives CSS colors ("rgb(r, g, b)") of the page's top and bottom edges. */
        @JavascriptInterface
        fun setEdgeColors(top: String, bottom: String) {
            val topColor = parseCssColor(top) ?: Color.WHITE
            val bottomColor = parseCssColor(bottom) ?: Color.WHITE
            runOnUiThread { if (b.offlineView.visibility != View.VISIBLE) applyBarColors(topColor, bottomColor) }
        }
    }

    private fun applyBarColors(top: Int, bottom: Int) {
        b.statusScrim.setBackgroundColor(top)
        b.navScrim.setBackgroundColor(bottom)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = ColorUtils.calculateLuminance(top) > 0.5
            isAppearanceLightNavigationBars = ColorUtils.calculateLuminance(bottom) > 0.5
        }
    }

    private inner class ShellClient : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
            routeNavigation(request.url.toString())

        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
            mainFrameFailed = false
            b.pageProgress.visibility = View.VISIBLE
        }

        override fun onPageFinished(view: WebView, url: String) {
            b.swipeRefresh.isRefreshing = false
            b.pageProgress.visibility = View.GONE
            view.evaluateJavascript(PAGE_SETUP_JS, null)
            if (!mainFrameFailed) {
                b.offlineView.visibility = View.GONE
                rememberUrl(url)
            }
        }

        // Fires for SPA pushState navigations too, so rooms are remembered as soon as they open.
        override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
            if (!mainFrameFailed) rememberUrl(url)
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            if (request.isForMainFrame) {
                mainFrameFailed = true
                showOffline()
            }
        }
    }

    private inner class ShellChromeClient : WebChromeClient() {
        override fun onProgressChanged(view: WebView, newProgress: Int) {
            b.pageProgress.setProgressCompat(newProgress, true)
        }

        override fun onShowFileChooser(
            webView: WebView,
            callback: ValueCallback<Array<Uri>>,
            params: FileChooserParams,
        ): Boolean {
            filePathCallback?.onReceiveValue(null)
            filePathCallback = callback
            // .puz has no registered MIME type, so accept anything and let the site validate.
            val intent = params.createIntent().apply {
                type = "*/*"
                addCategory(Intent.CATEGORY_OPENABLE)
                if (params.mode == FileChooserParams.MODE_OPEN_MULTIPLE) putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            }
            return try {
                fileChooser.launch(intent)
                true
            } catch (_: ActivityNotFoundException) {
                filePathCallback = null
                false
            }
        }

        /**
         * Popups (e.g. Firebase's signInWithPopup) get a throwaway WebView that only
         * exists to learn the target URL, which is then routed like any other link.
         */
        override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
            val popup = WebView(this@MainActivity)
            popup.webViewClient = object : WebViewClient() {
                private var handled = false
                private fun handle(url: String) {
                    if (handled || url == "about:blank") return
                    handled = true
                    if (!routeNavigation(url)) b.webView.loadUrl(url)
                    popup.post { popup.destroy() }
                }

                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    handle(request.url.toString())
                    return true
                }

                override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) = handle(url)
            }
            (resultMsg.obj as WebView.WebViewTransport).webView = popup
            resultMsg.sendToTarget()
            return true
        }
    }

    /** Returns true when the navigation was handled natively and the WebView must not load it. */
    private fun routeNavigation(url: String): Boolean {
        val uri = Uri.parse(url)
        return when {
            isGoogleAuth(uri) -> {
                showGoogleSignInDialog()
                true
            }
            isGameHost(uri.host) -> false
            uri.scheme == "http" || uri.scheme == "https" -> {
                openExternal(url)
                true
            }
            else -> {
                runCatching { startActivity(Intent(Intent.ACTION_VIEW, uri)) }
                true
            }
        }
    }

    private fun rememberUrl(url: String) {
        if (isGameUrl(url)) prefs.lastUrl = url
    }

    // endregion

    // region Refresh, offline & back

    private fun setUpRefresh() {
        b.swipeRefresh.setColorSchemeResources(R.color.brand)
        // Never refresh mid-solve: a downward drag on the grid is far more likely than a deliberate reload.
        b.swipeRefresh.setOnChildScrollUpCallback { _, _ ->
            b.offlineView.visibility != View.VISIBLE &&
                (b.webView.scrollY > 0 || pageCanScrollUp || isInPuzzle(b.webView.url))
        }
        b.swipeRefresh.setOnRefreshListener { retry() }
    }

    private fun retry() {
        b.offlineView.visibility = View.GONE
        val current = b.webView.url
        if (current.isNullOrEmpty() || current == "about:blank") {
            b.webView.loadUrl(prefs.lastUrl ?: homeUrl)
        } else {
            b.webView.reload()
        }
    }

    private fun showOffline() {
        b.swipeRefresh.isRefreshing = false
        b.pageProgress.visibility = View.GONE
        b.offlineView.visibility = View.VISIBLE
        val surface = getColor(R.color.surface)
        applyBarColors(surface, surface)
    }

    private fun isOnline(): Boolean {
        val cm = getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun setUpBackHandling() {
        onBackPressedDispatcher.addCallback(this) {
            val url = b.webView.url
            if (b.offlineView.visibility != View.VISIBLE && url != null && !isHome(url)) {
                // Inside a room or any sub-page: step back through the SPA's own history.
                if (b.webView.canGoBack()) b.webView.goBack() else b.webView.loadUrl(homeUrl)
                return@addCallback
            }
            val now = System.currentTimeMillis()
            if (now - lastBackPress < 2_000) {
                finish()
            } else {
                lastBackPress = now
                Toast.makeText(this@MainActivity, R.string.press_back_again, Toast.LENGTH_SHORT).show()
            }
        }
    }

    // endregion

    // region Dialogs & sheets

    private fun showGoogleSignInDialog() {
        if (googleDialog?.isShowing == true) return
        googleDialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.google_signin_title)
            .setMessage(R.string.google_signin_body)
            .setPositiveButton(R.string.got_it, null)
            .setNeutralButton(R.string.open_in_browser) { _, _ -> openExternal(homeUrl) }
            .show()
    }

    private fun showOnboarding() {
        if (supportFragmentManager.findFragmentByTag(OnboardingSheet.TAG) != null) return
        OnboardingSheet().show(supportFragmentManager, OnboardingSheet.TAG)
        prefs.onboardingSeen = true
    }

    private fun showCompanion() {
        if (supportFragmentManager.findFragmentByTag(CompanionSheet.TAG) != null) return
        CompanionSheet().show(supportFragmentManager, CompanionSheet.TAG)
    }

    // endregion

    companion object {
        private val GAME_HOSTS = listOf("crosswithfriends.com", "downforacross.com")

        fun isGameHost(host: String?): Boolean =
            host != null && GAME_HOSTS.any { host == it || host.endsWith(".$it") }

        fun isGameUrl(url: String): Boolean = isGameHost(Uri.parse(url).host)

        fun isHome(url: String): Boolean {
            val uri = Uri.parse(url)
            return !isGameHost(uri.host) || uri.path.isNullOrEmpty() || uri.path == "/"
        }

        fun isInPuzzle(url: String?): Boolean {
            val path = url?.let { Uri.parse(it).path } ?: return false
            return path.contains("/game/") || path.contains("/room/") || path.contains("/beta/")
        }

        fun isGoogleAuth(uri: Uri): Boolean {
            val host = uri.host ?: return false
            val path = uri.path.orEmpty()
            return host == "accounts.google.com" ||
                path.contains("/__/auth/handler") ||
                (host.endsWith("firebaseapp.com") && path.contains("auth"))
        }

        private val CSS_RGB = Regex("""rgba?\(\s*(\d+)[,\s]+(\d+)[,\s]+(\d+)(?:[,\s/]+([\d.]+))?""")

        /** Parses computed CSS colors; returns null for fully transparent values. */
        fun parseCssColor(css: String): Int? {
            val m = CSS_RGB.find(css) ?: return null
            val (r, g, bl) = m.destructured
            val alpha = m.groupValues[4].toFloatOrNull() ?: 1f
            if (alpha == 0f) return null
            return Color.rgb(r.toInt(), g.toInt(), bl.toInt())
        }

        /**
         * Re-enables pinch zoom on pages that lock the viewport, reports inner scroll position,
         * and reports the page's edge colors so the system bar strips can match (e.g. dark mode).
         */
        private const val PAGE_SETUP_JS = """
            (function() {
              var m = document.querySelector('meta[name=viewport]');
              if (!m) { m = document.createElement('meta'); m.name = 'viewport'; document.head.appendChild(m); }
              m.content = 'width=device-width, initial-scale=1, maximum-scale=5, user-scalable=yes';
              if (window.__companionScrollHook) { window.__companionReportEdges(); return; }
              function bgAt(y) {
                var el = document.elementFromPoint(window.innerWidth / 2, y);
                while (el && el.nodeType === 1) {
                  var c = getComputedStyle(el).backgroundColor;
                  if (c && c !== 'transparent' && !/rgba\([^)]*,\s*0\)$/.test(c)) return c;
                  el = el.parentElement;
                }
                // Nothing painted: the WebView's white default background shows through.
                return 'rgb(255, 255, 255)';
              }
              var last = '', timer = null;
              window.__companionReportEdges = function() {
                var top = bgAt(1), bottom = bgAt(window.innerHeight - 1);
                if (top + bottom === last) return;
                last = top + bottom;
                CompanionShell.setEdgeColors(top, bottom);
              };
              function schedule() {
                if (timer) return;
                timer = setTimeout(function() { timer = null; window.__companionReportEdges(); }, 200);
              }
              new MutationObserver(schedule).observe(document.documentElement,
                { attributes: true, attributeFilter: ['class', 'style', 'data-theme'], subtree: true, childList: true });
              window.addEventListener('resize', schedule);
              window.addEventListener('scroll', schedule, true);
              window.__companionReportEdges();
              window.__companionScrollHook = true;
              document.addEventListener('touchstart', function(e) {
                var el = e.target, up = false;
                while (el && el.nodeType === 1 && el !== document.body && el !== document.documentElement) {
                  if (el.scrollTop > 0) { up = true; break; }
                  el = el.parentElement;
                }
                if (!up) up = (document.scrollingElement || document.documentElement).scrollTop > 0;
                CompanionShell.setCanScrollUp(up);
              }, { passive: true, capture: true });
            })();
        """
    }
}
