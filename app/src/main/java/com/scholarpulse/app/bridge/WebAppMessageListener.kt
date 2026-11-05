package com.scholarpulse.app.bridge

import android.net.Uri
import android.webkit.WebView
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewCompat

class WebAppMessageListener(
    private val dispatcher: NativeBridgeDispatcher
) : WebViewCompat.WebMessageListener {

    override fun onPostMessage(
        view: WebView,
        message: WebMessageCompat,
        sourceOrigin: Uri,
        isMainFrame: Boolean,
        replyProxy: JavaScriptReplyProxy
    ) {
        val messageData = message.data ?: return
        val originStr = sourceOrigin.toString()
        dispatcher.handleRawMessage(messageData, originStr, replyProxy)
    }
}
