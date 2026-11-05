package com.scholarpulse.app.bridge

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
}
