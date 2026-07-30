package com.laobai.demo

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient

class CaseActivity : Activity() {
    private lateinit var webView: WebView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_case)
        window.statusBarColor = Color.WHITE
        window.navigationBarColor = Color.WHITE

        webView = findViewById(R.id.caseWebView)
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = true
            allowContentAccess = false
            builtInZoomControls = false
            displayZoomControls = false
        }
        webView.isHorizontalScrollBarEnabled = false
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest,
            ): Boolean = !request.url.toString().startsWith(ASSET_PREFIX)
        }

        val asset = when (intent.getStringExtra(EXTRA_CASE)) {
            CASE_TRIGGER -> "trigger-health.html"
            else -> "always-on-form.html"
        }
        webView.loadUrl("$ASSET_PREFIX$asset")
    }

    override fun onBackPressed() {
        if (::webView.isInitialized && webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        if (::webView.isInitialized) {
            webView.stopLoading()
            webView.destroy()
        }
        super.onDestroy()
    }

    companion object {
        const val CASE_ALWAYS_ON = "always_on"
        const val CASE_TRIGGER = "trigger"

        private const val EXTRA_CASE = "com.laobai.demo.extra.CASE"
        private const val ASSET_PREFIX = "file:///android_asset/"

        fun createIntent(context: Context, caseName: String): Intent =
            Intent(context, CaseActivity::class.java).putExtra(EXTRA_CASE, caseName)
    }
}
