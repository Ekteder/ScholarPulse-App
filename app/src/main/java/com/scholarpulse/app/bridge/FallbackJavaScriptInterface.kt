package com.scholarpulse.app.bridge

import android.util.Log
import android.webkit.JavascriptInterface

class FallbackJavaScriptInterface(
    private val dispatcher: NativeBridgeDispatcher,
    private val getCurrentOrigin: () -> String
) {
    @JavascriptInterface
    fun postMessage(messageJson: String) {
        val currentOrigin = getCurrentOrigin()
        dispatcher.handleRawMessage(messageJson, currentOrigin, null)
    }

    @JavascriptInterface
    fun updateTheme(theme: String) {
        Log.i("FallbackBridge", "updateTheme called with theme=$theme")
        val isDark = theme.equals("dark", ignoreCase = true)
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            dispatcher.onThemeChange?.invoke(isDark)
        }
    }

    @JavascriptInterface
    fun getNativeTimerSyncState(): String {
        return dispatcher.getTimerSyncState()
    }

    @JavascriptInterface
    fun acknowledgePendingReview(idempotencyKey: String) {
        dispatcher.acknowledgePendingReview(idempotencyKey)
    }
}
