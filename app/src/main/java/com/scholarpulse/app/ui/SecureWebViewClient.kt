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
        // Inject native shell detection flags as early as possible
        view.evaluateJavascript(
            """
            (function() {
                window.__IS_NATIVE_SHELL__ = true;
                if (!window.ScholarPulseBridge) {
                    window.ScholarPulseBridge = window.nativeBridge || window.__ScholarPulseNativeBridge;
                }
            })();
            """.trimIndent(),
            null
        )
    }

    override fun onPageFinished(view: WebView, url: String?) {
        super.onPageFinished(view, url)
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
