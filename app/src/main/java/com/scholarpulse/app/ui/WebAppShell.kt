package com.scholarpulse.app.ui

import android.annotation.SuppressLint
import android.graphics.Color
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.scholarpulse.app.BuildConfig
import com.scholarpulse.app.R
import com.scholarpulse.app.bridge.FallbackJavaScriptInterface
import com.scholarpulse.app.bridge.NativeBridgeDispatcher
import com.scholarpulse.app.bridge.SafeAreaInsets
import com.scholarpulse.app.bridge.WebAppMessageListener
import com.scholarpulse.app.ui.theme.Blue600
import com.scholarpulse.app.ui.theme.Slate200
import com.scholarpulse.app.ui.theme.Slate400
import com.scholarpulse.app.ui.theme.Slate50
import com.scholarpulse.app.ui.theme.Slate600
import com.scholarpulse.app.ui.theme.Slate800
import com.scholarpulse.app.ui.theme.Slate900
import com.scholarpulse.app.ui.theme.Slate950

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebAppShell(
    targetUrl: String,
    bridgeDispatcher: NativeBridgeDispatcher,
    isDarkTheme: Boolean = isSystemInDarkTheme(),
    onWebReady: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var webViewInstance by remember { mutableStateOf<WebView?>(null) }
    var canGoBack by remember { mutableStateOf(false) }
    var isOffline by remember { mutableStateOf(false) }
    var initialLoadSuccess by remember { mutableStateOf(false) }

    // Intercept hardware/predictive back gestures to navigate WebView history
    BackHandler(enabled = canGoBack) {
        webViewInstance?.let { wv ->
            if (wv.canGoBack()) {
                wv.goBack()
            }
        }
    }

    // Dynamic anti-flash background color matching current theme
    val nativeBgColor = if (isDarkTheme) 0xFF020617.toInt() else 0xFFF8FAFC.toInt()

    LaunchedEffect(isDarkTheme, webViewInstance) {
        webViewInstance?.setBackgroundColor(nativeBgColor)
    }

    val topInsetBg = if (isDarkTheme) Slate900 else androidx.compose.ui.graphics.Color.White

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(topInsetBg)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                val swipeRefresh = SwipeRefreshLayout(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    setColorSchemeColors(0xFF2563EB.toInt())
                    setProgressBackgroundColorSchemeColor(
                        if (isDarkTheme) 0xFF0F172A.toInt() else Color.WHITE
                    )
                }

                val webView = WebView(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    setBackgroundColor(nativeBgColor)

                    // Harden WebView Settings per OWASP Mobile Guidelines
                    settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        allowFileAccess = false
                        allowContentAccess = false
                        mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                        setGeolocationEnabled(false)
                        setSupportZoom(false)
                        builtInZoomControls = false
                        displayZoomControls = false
                        useWideViewPort = true
                        loadWithOverviewMode = true

                        // Custom user agent identifier preserving browser compat
                        val originalUa = userAgentString
                        userAgentString = "$originalUa ScholarPulseAndroid/${BuildConfig.VERSION_NAME}"
                    }

                    // Configure secure cookie synchronization
                    val cookieManager = CookieManager.getInstance()
                    cookieManager.setAcceptCookie(true)
                    cookieManager.setAcceptThirdPartyCookies(this, true)

                    // Attach bidirectional IPC bridges
                    if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
                        WebViewCompat.addWebMessageListener(
                            this,
                            "nativeBridge",
                            bridgeDispatcher.allowedOrigins,
                            WebAppMessageListener(bridgeDispatcher)
                        )
                    }

                    val activeLoadedUrl = java.util.concurrent.atomic.AtomicReference(targetUrl)

                    // Multi-channel fallback JavascriptInterface (supports all standard window bridge identifiers)
                    val fallbackInterface = FallbackJavaScriptInterface(bridgeDispatcher) {
                        activeLoadedUrl.get()
                    }
                    addJavascriptInterface(fallbackInterface, "AndroidBridge")
                    addJavascriptInterface(fallbackInterface, "__ScholarPulseNativeBridge")
                    addJavascriptInterface(fallbackInterface, "ScholarPulseBridge")
                    addJavascriptInterface(fallbackInterface, "Android")

                    bridgeDispatcher.attachWebView(this)

                    // Dual-layer WindowInsets listener to inject DP safe-area variables
                    ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
                        val density = view.resources.displayMetrics.density
                        val statusBars = insets.getInsets(
                            WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.displayCutout()
                        )
                        val navBars = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
                        val ime = insets.getInsets(WindowInsetsCompat.Type.ime())

                        val topDp = (statusBars.top / density).toInt()
                        val bottomDp = (navBars.bottom / density).toInt()
                        val leftDp = (statusBars.left / density).toInt()
                        val rightDp = (statusBars.right / density).toInt()
                        val imeDp = (ime.bottom / density).toInt()

                        bridgeDispatcher.currentInsets = SafeAreaInsets(
                            top = topDp,
                            bottom = bottomDp,
                            left = leftDp,
                            right = rightDp
                        )

                        evaluateJavascript(
                            """
                            (function() {
                                var root = document.documentElement;
                                if (root) {
                                    root.style.setProperty('--sat', '${topDp}px');
                                    root.style.setProperty('--sab', '${bottomDp}px');
                                    root.style.setProperty('--sal', '${leftDp}px');
                                    root.style.setProperty('--sar', '${rightDp}px');
                                    root.style.setProperty('--ime-height', '${imeDp}px');
                                }
                                var styleId = '__sp_native_safe_area__';
                                var style = document.getElementById(styleId);
                                if (!style) {
                                    style = document.createElement('style');
                                    style.id = styleId;
                                    (document.head || document.documentElement).appendChild(style);
                                }
                                style.textContent = `
                                    header {
                                        padding-top: max(${topDp}px, 0.75rem) !important;
                                    }
                                    main {
                                        padding-bottom: max(${bottomDp}px, 1.5rem) !important;
                                    }
                                    [data-pwa-install],
                                    .pwa-install-button,
                                    button:has(svg.lucide-download) {
                                        display: none !important;
                                    }
                                `;
                            })();
                            """.trimIndent(),
                            null
                        )

                        insets
                    }

                    webViewClient = SecureWebViewClient(
                        context = ctx,
                        bridgeDispatcher = bridgeDispatcher,
                        onPageLoaded = { url ->
                            activeLoadedUrl.set(url)
                            canGoBack = canGoBack()
                            isOffline = false
                            initialLoadSuccess = true
                            onWebReady()
                        },
                        onPageFailed = { errorCode, description ->
                            if (!initialLoadSuccess) {
                                isOffline = true
                            }
                            onWebReady()
                        },
                        onGoogleSignInRequested = {
                            bridgeDispatcher.triggerGoogleSignIn()
                        }
                    )

                    webChromeClient = CustomWebChromeClient { progress ->
                        if (progress >= 85) {
                            onWebReady()
                        }
                    }

                    loadUrl(targetUrl)
                }

                // Coordinate pull-to-refresh: only activate when webView.scrollY == 0
                swipeRefresh.setOnChildScrollUpCallback { _, _ ->
                    webView.scrollY > 0
                }

                swipeRefresh.setOnRefreshListener {
                    webView.evaluateJavascript(
                        "window.dispatchEvent(new CustomEvent('scholarpulse:pull-refresh'));",
                        null
                    )
                    swipeRefresh.postDelayed({
                        swipeRefresh.isRefreshing = false
                    }, 1200L)
                }

                swipeRefresh.addView(webView)
                webViewInstance = webView
                swipeRefresh
            },
            update = { swipeRefresh ->
                swipeRefresh.setProgressBackgroundColorSchemeColor(
                    if (isDarkTheme) 0xFF0F172A.toInt() else Color.WHITE
                )
            }
        )

        // Native Compose Offline State Overlay if initial connection fails
        if (isOffline) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(if (isDarkTheme) Slate950 else Slate50)
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_splash_logo),
                        contentDescription = "ScholarPulse Logo",
                        modifier = Modifier.size(80.dp),
                        tint = androidx.compose.ui.graphics.Color.Unspecified
                    )

                    Spacer(modifier = Modifier.height(24.dp))

                    Text(
                        text = "Unable to Connect",
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontWeight = FontWeight.Bold,
                            color = if (isDarkTheme) Slate50 else Slate900,
                            fontSize = 22.sp
                        ),
                        textAlign = TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = "ScholarPulse requires an internet connection for real-time workspace sync and timer tracking.",
                        style = MaterialTheme.typography.bodyLarge.copy(
                            color = if (isDarkTheme) Slate400 else Slate600,
                            fontSize = 15.sp,
                            lineHeight = 22.sp
                        ),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )

                    Spacer(modifier = Modifier.height(32.dp))

                    Button(
                        onClick = {
                            isOffline = false
                            webViewInstance?.loadUrl(targetUrl)
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Blue600,
                            contentColor = androidx.compose.ui.graphics.Color.White
                        ),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth(0.7f)
                            .height(48.dp) // Minimum 48dp touch target
                    ) {
                        Text(
                            text = "Retry Connection",
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 16.sp
                        )
                    }
                }
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            webViewInstance?.let { wv ->
                wv.stopLoading()
                wv.destroy()
            }
        }
    }
}
