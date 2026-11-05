package com.scholarpulse.app.ui

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.util.Log
import android.webkit.SslErrorHandler
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewAssetLoader
import com.scholarpulse.app.BuildConfig

class SecureWebViewClient(
    private val context: Context,
    private val bridgeDispatcher: com.scholarpulse.app.bridge.NativeBridgeDispatcher? = null,
    private val onPageLoaded: (url: String) -> Unit,
    private val onPageFailed: (errorCode: Int, description: String) -> Unit,
    private val onGoogleSignInRequested: (() -> Unit)? = null
) : WebViewClient() {

    private val tag = "SecureWebViewClient"

    private val assetLoader = WebViewAssetLoader.Builder()
        .setDomain("appassets.androidplatform.net")
        .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context))
        .build()

    private val allowedHosts = buildSet {
        add("scholarpulsev2.vercel.app")
        add("appassets.androidplatform.net")
        if (BuildConfig.DEBUG) {
            add("localhost")
            add("10.0.2.2")
            add("127.0.0.1")
        }
    }

    override fun shouldInterceptRequest(
        view: WebView,
        request: WebResourceRequest
    ): WebResourceResponse? {
        // Intercept local assets via virtual secure domain
        return assetLoader.shouldInterceptRequest(request.url)
    }

    override fun shouldOverrideUrlLoading(
        view: WebView,
        request: WebResourceRequest
    ): Boolean {
        val uri = request.url ?: return false
        return handleUrlLoading(uri)
    }

    @Deprecated("Deprecated in Java")
    override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
        val uri = Uri.parse(url) ?: return false
        return handleUrlLoading(uri)
    }

    private fun handleUrlLoading(uri: Uri): Boolean {
        val host = uri.host?.lowercase() ?: ""
        val scheme = uri.scheme?.lowercase() ?: ""
        val path = uri.path ?: ""

        // Intercept Google OAuth initiation to prevent 403 disallowed_useragent
        if (path == "/api/auth/google" || path.startsWith("/api/auth/google") || host == "accounts.google.com") {
            Log.i(tag, "Intercepted Google OAuth URL ($uri); triggering native CredentialManager sign-in.")
            onGoogleSignInRequested?.invoke()
            return true
        }

        // Handle app-internal allowed origins
        if ((scheme == "https" || (BuildConfig.DEBUG && scheme == "http")) && allowedHosts.contains(host)) {
            return false // Let WebView render directly
        }

        // Handle external links (tel, mailto, external marketing, or docs) via external system browser
        return try {
            val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            Log.e(tag, "Failed to launch external URL handler for: $uri", e)
            true
        }
    }

    override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
        super.onPageStarted(view, url, favicon)
        // Inject native shell detection flags and theme mutation observer as early as possible
        view.evaluateJavascript(
            """
            (function() {
                window.__IS_NATIVE_SHELL__ = true;
                if (!window.ScholarPulseBridge) {
                    window.ScholarPulseBridge = window.nativeBridge || window.__ScholarPulseNativeBridge;
                }

                function syncTheme() {
                    var isDark = document.documentElement.classList.contains('dark');
                    var bridge = window.ScholarPulseBridge || window.nativeBridge || window.__ScholarPulseNativeBridge;
                    if (bridge && typeof bridge.postMessage === 'function') {
                        bridge.postMessage(JSON.stringify({
                            jsonrpc: "2.0",
                            id: "theme_sync_" + Date.now(),
                            method: "system.updateTheme",
                            params: { theme: isDark ? "dark" : "light" }
                        }));
                    }
                }

                syncTheme();

                if (!window.__sp_theme_observer_attached__) {
                    window.__sp_theme_observer_attached__ = true;
                    var observer = new MutationObserver(function(mutations) {
                        for (var i = 0; i < mutations.length; i++) {
                            if (mutations[i].attributeName === 'class') {
                                syncTheme();
                                break;
                            }
                        }
                    });
                    observer.observe(document.documentElement, { attributes: true, attributeFilter: ['class'] });
                }
            })();
            """.trimIndent(),
            null
        )
    }

    override fun onPageFinished(view: WebView, url: String?) {
        super.onPageFinished(view, url)
        val insets = bridgeDispatcher?.currentInsets
        val topDp = insets?.top ?: 36
        val bottomDp = insets?.bottom ?: 24
        val leftDp = insets?.left ?: 0
        val rightDp = insets?.right ?: 0

        // Inject safe area CSS, suppress redundant PWA install prompt, and attach live theme observer
        view.evaluateJavascript(
            """
            (function() {
                var root = document.documentElement;
                if (root) {
                    root.style.setProperty('--sat', '0px');
                    root.style.setProperty('--sab', '0px');
                    root.style.setProperty('--sal', '0px');
                    root.style.setProperty('--sar', '0px');
                }
                var styleId = '__sp_native_hide_pwa__';
                var style = document.getElementById(styleId);
                if (!style) {
                    style = document.createElement('style');
                    style.id = styleId;
                    (document.head || document.documentElement).appendChild(style);
                }
                style.textContent = `
                    [data-pwa-install],
                    .pwa-install-button,
                    button:has(svg.lucide-download) {
                        display: none !important;
                    }
                `;

                function reportTheme() {
                    try {
                        var isDark = document.documentElement.classList.contains('dark');
                        var bridge = window.__ScholarPulseNativeBridge || window.ScholarPulseBridge;
                        if (bridge && typeof bridge.updateTheme === 'function') {
                            bridge.updateTheme(isDark ? 'dark' : 'light');
                        }
                    } catch (e) {
                        console.warn('[NativeBridge] Failed to report theme:', e);
                    }
                }

                reportTheme();

                if (!window.__sp_theme_observer_attached__) {
                    window.__sp_theme_observer_attached__ = true;
                    var observer = new MutationObserver(function() {
                        reportTheme();
                    });
                    observer.observe(document.documentElement, {
                        attributes: true,
                        attributeFilter: ['class']
                    });
                }
            })();
            """.trimIndent(),
            null
        )

        if (url != null) {
            onPageLoaded(url)
        }
    }

    override fun onReceivedSslError(
        view: WebView?,
        handler: SslErrorHandler?,
        error: SslError?
    ) {
        // OWASP Mobile Top 10 Requirement: Reject invalid/expired SSL certificates immediately
        Log.e(tag, "FATAL: SSL Error received: ${error?.primaryError}. Aborting connection immediately.")
        handler?.cancel()
    }

    override fun onReceivedError(
        view: WebView?,
        request: WebResourceRequest?,
        error: WebResourceError?
    ) {
        super.onReceivedError(view, request, error)
        if (request?.isForMainFrame == true) {
            val errorCode = error?.errorCode ?: -1
            val description = error?.description?.toString() ?: "Unknown network error"
            Log.w(tag, "Main frame error: $errorCode - $description on ${request.url}")
            onPageFailed(errorCode, description)
        }
    }
}
