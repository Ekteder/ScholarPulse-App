package com.scholarpulse.app.ui

import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import com.scholarpulse.app.BuildConfig

class CustomWebChromeClient(
    private val onProgressChange: (progress: Int) -> Unit
) : WebChromeClient() {

    private val tag = "ScholarPulseWebChrome"

    override fun onProgressChanged(view: android.webkit.WebView?, newProgress: Int) {
        super.onProgressChanged(view, newProgress)
        onProgressChange(newProgress)
    }

    override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
        if (BuildConfig.DEBUG && consoleMessage != null) {
            val logMessage = "[WebConsole] ${consoleMessage.message()} (${consoleMessage.sourceId()}:${consoleMessage.lineNumber()})"
            when (consoleMessage.messageLevel()) {
                ConsoleMessage.MessageLevel.ERROR -> Log.e(tag, logMessage)
                ConsoleMessage.MessageLevel.WARNING -> Log.w(tag, logMessage)
                else -> Log.d(tag, logMessage)
            }
        }
        return true
    }
}
