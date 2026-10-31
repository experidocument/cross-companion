package com.crosscompanion.app

import android.annotation.SuppressLint
import android.graphics.Color
import android.os.Bundle
import android.text.TextUtils
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.crosscompanion.app.databinding.ActivityGuideBinding

/** In-app walkthrough for deploying the Apps Script webhook, with the script inlined from assets. */
class GuideActivity : AppCompatActivity() {

    private val script by lazy { assets.open("Code.gs").bufferedReader().use { it.readText() } }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT))
        super.onCreate(savedInstanceState)
        val b = ActivityGuideBinding.inflate(layoutInflater)
        setContentView(b.root)
        ViewCompat.setOnApplyWindowInsetsListener(b.root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updatePadding(left = bars.left, top = bars.top, right = bars.right, bottom = bars.bottom)
            WindowInsetsCompat.CONSUMED
        }

        b.toolbar.setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material)
        b.toolbar.setNavigationOnClickListener { finish() }

        b.web.settings.javaScriptEnabled = true
        b.web.addJavascriptInterface(Bridge(), "CompanionGuide")
        b.web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                openExternal(request.url.toString())
                return true
            }
        }
        val html = assets.open("webhook_guide.html").bufferedReader().use { it.readText() }
            .replace("{{SCRIPT}}", TextUtils.htmlEncode(script))
        b.web.loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
    }

    private inner class Bridge {
        @JavascriptInterface
        fun copyScript() {
            runOnUiThread {
                copyToClipboard("Apps Script", script)
                toast(R.string.script_copied)
            }
        }
    }
}
