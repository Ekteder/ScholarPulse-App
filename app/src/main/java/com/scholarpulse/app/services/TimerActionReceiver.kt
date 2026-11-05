package com.scholarpulse.app.services

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.scholarpulse.app.bridge.NativeBridgeDispatcher
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * BroadcastReceiver intercepting notification action clicks from the persistent study timer.
 * Routes commands immediately to TimerForegroundService and emits `timer.actionTriggered`
 * to the WebView NativeBridgeDispatcher so the web React context synchronizes in real time.
 */
class TimerActionReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "TimerActionReceiver"
    }

    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) return
        val action = intent.action ?: return
        Log.d(TAG, "Notification action received: $action")

        // 1. Route command to TimerForegroundService
        val serviceIntent = Intent(context, TimerForegroundService::class.java).apply {
            this.action = action
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start service for action $action", e)
        }

        // 2. Map notification action to web bridge contract
        val bridgeAction = when (action) {
            TimerForegroundService.ACTION_PAUSE -> "pause"
            TimerForegroundService.ACTION_RESUME -> "resume"
            TimerForegroundService.ACTION_FINISH, TimerForegroundService.ACTION_STOP -> "finish"
            TimerForegroundService.ACTION_SKIP_PHASE, TimerForegroundService.ACTION_SKIP_BREAK -> "skipPhase"
            TimerForegroundService.ACTION_DISCARD -> "discard"
            else -> action
        }

        // 3. Dispatch unsolicited event to web React context
        val eventPayload = buildJsonObject {
            put("action", bridgeAction)
            put("source", "notification")
        }

        val activeBridge = TimerForegroundService.dispatcher ?: NativeBridgeDispatcher.activeDispatcher
        activeBridge?.sendEvent("timer.actionTriggered", eventPayload)
        Log.d(TAG, "Dispatched timer.actionTriggered to bridge: $bridgeAction")
    }
}
