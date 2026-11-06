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

    @JavascriptInterface
    fun onRemoteTimerSync(action: String, accumulatedSeconds: Long, updatedAt: Long, originDeviceId: String) {
        com.scholarpulse.app.services.TimerForegroundService.dispatchRemoteSync(
            context = dispatcher.context,
            action = action,
            accumulatedSeconds = accumulatedSeconds,
            updatedAt = updatedAt,
            originDeviceId = originDeviceId
        )
    }

    @JavascriptInterface
    fun onRemoteTimerSyncJson(jsonPayload: String) {
        try {
            val json = org.json.JSONObject(jsonPayload)
            com.scholarpulse.app.services.TimerForegroundService.dispatchRemoteSync(
                context = dispatcher.context,
                action = json.optString("action", "PAUSE"),
                accumulatedSeconds = json.optLong("accumulatedSeconds", json.optLong("remainingMs", -1L)),
                updatedAt = json.optLong("updatedAt", System.currentTimeMillis()),
                originDeviceId = json.optString("originDeviceId", "")
            )
        } catch (e: Exception) {
            Log.w("FallbackBridge", "Failed to parse onRemoteTimerSyncJson", e)
        }
    }
}
