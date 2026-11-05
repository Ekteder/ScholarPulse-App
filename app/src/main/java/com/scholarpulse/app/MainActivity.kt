package com.scholarpulse.app

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.animation.doOnEnd
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import com.scholarpulse.app.auth.CookieSessionManager
import com.scholarpulse.app.auth.CredentialManagerHelper
import com.scholarpulse.app.auth.GoogleTokenExchanger
import com.scholarpulse.app.bridge.NativeBridgeDispatcher
import com.scholarpulse.app.services.TimerForegroundService
import com.scholarpulse.app.ui.WebAppShell
import com.scholarpulse.app.ui.theme.ScholarPulseTheme
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class MainActivity : ComponentActivity() {

    private var isWebReady by mutableStateOf(false)
    private var explicitThemeDark by mutableStateOf<Boolean?>(null)
    private lateinit var bridgeDispatcher: NativeBridgeDispatcher

    override fun onCreate(savedInstanceState: Bundle?) {
        // 1. Install Android 12+ Splash Screen before super.onCreate()
        val splashScreen = installSplashScreen()
        super.onCreate(savedInstanceState)

        // 2. Android 15 & 16 Mandatory Edge-to-Edge display
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT)
        )

        // 3. Notch and Camera Cutout Immersion (API 28+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }

        // 4. Initialize Native Bridge Dispatcher & Auth Subsystem
        val credentialManagerHelper = CredentialManagerHelper(this)
        val cookieSessionManager = CookieSessionManager(this)
        val googleTokenExchanger = GoogleTokenExchanger()

        bridgeDispatcher = NativeBridgeDispatcher(
            context = this,
            scope = lifecycleScope
        ).apply {
            this.activityProvider = { this@MainActivity }
            this.credentialManagerHelper = credentialManagerHelper
            this.cookieSessionManager = cookieSessionManager
            this.tokenExchanger = googleTokenExchanger
            onWebReady = {
                runOnUiThread { isWebReady = true }
            }
            onThemeChange = { isDark ->
                runOnUiThread {
                    explicitThemeDark = isDark
                    syncSystemBars(isDark)
                }
            }
        }

        NativeBridgeDispatcher.activeDispatcher = bridgeDispatcher
        TimerForegroundService.dispatcher = bridgeDispatcher

        // 5. Hold splash screen until Next.js DOM hydrates
        splashScreen.setKeepOnScreenCondition { !isWebReady }

        // 6. Smooth Splash Screen Exit Animation (Alpha fade + subtle scale)
        splashScreen.setOnExitAnimationListener { splashScreenViewProvider ->
            val splashView = splashScreenViewProvider.view
            val alphaAnim = ObjectAnimator.ofFloat(splashView, View.ALPHA, 1f, 0f)
            val scaleXAnim = ObjectAnimator.ofFloat(splashView, View.SCALE_X, 1f, 1.03f)
            val scaleYAnim = ObjectAnimator.ofFloat(splashView, View.SCALE_Y, 1f, 1.03f)

            AnimatorSet().apply {
                playTogether(alphaAnim, scaleXAnim, scaleYAnim)
                duration = 260L
                interpolator = AccelerateDecelerateInterpolator()
                doOnEnd { splashScreenViewProvider.remove() }
                start()
            }
        }

        // 7. Safety timeout fallback: dismiss splash after 3500ms max in case of slow network
        Handler(Looper.getMainLooper()).postDelayed({
            if (!isWebReady) {
                isWebReady = true
            }
        }, 3500L)

        // 8. Handle initial launch intent (e.g. notification deep link)
        handleNotificationIntent(intent)

        // 9. Render Jetpack Compose Root Container
        setContent {
            val systemDark = isSystemInDarkTheme()
            val effectiveDark = explicitThemeDark ?: systemDark

            syncSystemBars(effectiveDark)

            ScholarPulseTheme(darkTheme = effectiveDark) {
                WebAppShell(
                    targetUrl = BuildConfig.WEB_APP_URL,
                    bridgeDispatcher = bridgeDispatcher,
                    isDarkTheme = effectiveDark,
                    onWebReady = { isWebReady = true }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleNotificationIntent(intent)
    }

    override fun onDestroy() {
        if (NativeBridgeDispatcher.activeDispatcher == bridgeDispatcher) {
            NativeBridgeDispatcher.activeDispatcher = null
        }
        if (TimerForegroundService.dispatcher == bridgeDispatcher) {
            TimerForegroundService.dispatcher = null
        }
        super.onDestroy()
    }

    private fun handleNotificationIntent(intent: Intent?) {
        if (intent == null) return
        val tab = intent.getStringExtra("tab") ?: intent.data?.getQueryParameter("tab")
        val targetId = intent.getStringExtra("targetId") ?: intent.data?.getQueryParameter("targetId")

        if (!tab.isNullOrBlank()) {
            bridgeDispatcher.sendEvent(
                "navigation.deepLink",
                buildJsonObject {
                    put("tab", tab)
                    if (!targetId.isNullOrBlank()) {
                        put("targetId", targetId)
                    }
                }
            )
            if (tab == "timer") {
                bridgeDispatcher.sendEvent(
                    "navigation.tabRequested",
                    buildJsonObject { put("tab", "timer") }
                )
            }
        }
    }

    private fun syncSystemBars(isDark: Boolean) {
        val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)
        // Status bar: true = dark icons (light theme), false = light icons (dark theme)
        windowInsetsController.isAppearanceLightStatusBars = !isDark
        // Navigation bar: true = dark icons/pill (light theme), false = light icons/pill (dark theme)
        windowInsetsController.isAppearanceLightNavigationBars = !isDark
    }
}
