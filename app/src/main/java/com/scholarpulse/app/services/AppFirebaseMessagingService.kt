package com.scholarpulse.app.services

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.scholarpulse.app.BuildConfig
import com.scholarpulse.app.MainActivity
import com.scholarpulse.app.R
import com.scholarpulse.app.ScholarPulseApp
import com.scholarpulse.app.auth.CookieSessionManager
import com.scholarpulse.app.bridge.NativeBridgeDispatcher
import com.scholarpulse.app.workers.OfflineSyncWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * Service managing Firebase Cloud Messaging (FCM v1) notifications, token rotation,
 * and silent cloud synchronization triggers.
 */
class AppFirebaseMessagingService : FirebaseMessagingService() {

    companion object {
        private const val TAG = "AppFirebaseMessaging"
        private const val PREFS_NAME = "scholarpulse_prefs"
        private const val KEY_FCM_TOKEN = "fcm_registration_token"

        private val json = Json {
            ignoreUnknownKeys = true
            isLenient = true
        }

        fun getCachedToken(context: Context): String? {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return prefs.getString(KEY_FCM_TOKEN, null)
        }

        fun cacheToken(context: Context, token: String) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().putString(KEY_FCM_TOKEN, token).apply()
        }

        /**
         * Dispatches device registration token to backend /api/notifications/device-token.
         */
        suspend fun registerDeviceToken(context: Context, token: String) {
            val sessionToken = CookieSessionManager(context).getSessionToken()
            if (sessionToken.isNullOrBlank()) {
                Log.d(TAG, "No active session cookie found; skipping backend device token upload")
                return
            }

            val deviceId = try {
                Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: "android_device"
            } catch (_: Exception) {
                "android_device"
            }

            val payload = buildJsonObject {
                put("fcmToken", token)
                put("deviceId", deviceId)
                put("platform", "android")
                put("deviceModel", Build.MODEL)
                put("appVersion", BuildConfig.VERSION_NAME)
            }

            val payloadJson = json.encodeToString(payload)
            val primaryUrl = "${BuildConfig.WEB_APP_URL.trimEnd('/')}/api/notifications/device-token"

            val success = postDeviceTokenToUrl(primaryUrl, payloadJson, sessionToken)
            if (!success && BuildConfig.DEBUG && !primaryUrl.contains("10.0.2.2")) {
                postDeviceTokenToUrl("http://10.0.2.2:3000/api/notifications/device-token", payloadJson, sessionToken)
            }
        }

        private fun postDeviceTokenToUrl(urlString: String, payloadJson: String, sessionToken: String): Boolean {
            return try {
                val url = URL(urlString)
                val connection = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 10000
                    readTimeout = 10000
                    doInput = true
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    setRequestProperty("Accept", "application/json")
                    setRequestProperty("Cookie", "sp_session=$sessionToken")
                    setRequestProperty("User-Agent", "ScholarPulseAndroid/${BuildConfig.VERSION_NAME}")
                }

                OutputStreamWriter(connection.outputStream, Charsets.UTF_8).use { writer ->
                    writer.write(payloadJson)
                    writer.flush()
                }

                val responseCode = connection.responseCode
                val isSuccess = responseCode in 200..299

                if (isSuccess) {
                    val body = BufferedReader(InputStreamReader(connection.inputStream, Charsets.UTF_8)).use { it.readText() }
                    Log.i(TAG, "Successfully registered device token at $urlString: $body")
                } else {
                    Log.w(TAG, "Failed to register device token at $urlString (HTTP $responseCode)")
                }

                connection.disconnect()
                isSuccess
            } catch (e: Exception) {
                Log.e(TAG, "Error posting device token to $urlString: ${e.message}", e)
                false
            }
        }
    }

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        Log.i(TAG, "New FCM registration token received: $token")
        cacheToken(applicationContext, token)

        CoroutineScope(Dispatchers.IO).launch {
            registerDeviceToken(applicationContext, token)
        }
    }

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        super.onMessageReceived(remoteMessage)
        Log.d(TAG, "FCM message received from: ${remoteMessage.from}")

        // 1. Silent data payload handling (e.g. workspace refresh or sync triggers)
        val data = remoteMessage.data
        if (data.isNotEmpty()) {
            val eventType = data["eventType"] ?: data["type"]
            if (eventType == "workspace:refresh" || eventType == "sync:refresh") {
                Log.i(TAG, "Received workspace:refresh trigger via FCM data. Enqueuing sync.")
                OfflineSyncWorker.enqueueImmediateSync(applicationContext)
                NativeBridgeDispatcher.activeDispatcher?.sendEvent("workspace.refresh", null)
            }
        }

        // 2. Visible Notification payload handling (supports both standard notification and pure data payloads)
        val title = remoteMessage.notification?.title ?: data["title"]
        val body = remoteMessage.notification?.body ?: data["body"]

        if (!title.isNullOrBlank() || !body.isNullOrBlank()) {
            val targetUserId = data["userId"]
            if (!targetUserId.isNullOrBlank() && targetUserId != "all") {
                val currentUserId = CookieSessionManager(applicationContext).getUserIdFromSessionToken()
                if (currentUserId != null && currentUserId != targetUserId) {
                    Log.w(
                        TAG,
                        "Silently dropping push notification targeted to '$targetUserId' because phone is currently logged in as '$currentUserId'"
                    )
                    return
                }
                if (currentUserId == null) {
                    Log.w(
                        TAG,
                        "Silently dropping push notification targeted to '$targetUserId' because no active user is logged in on this phone"
                    )
                    return
                }
                Log.d(TAG, "Push notification targeting active user verified: $targetUserId")
            }
            showNotification(title ?: "ScholarPulse", body ?: "", data)
        }
    }

    private fun showNotification(
        title: String,
        body: String,
        data: Map<String, String>
    ) {
        if (!NotificationManagerCompat.from(this).areNotificationsEnabled()) {
            Log.w(TAG, "Post notifications permission disabled; ignoring FCM display notification.")
            return
        }

        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            ?: return

        val targetUrl = data["url"] ?: BuildConfig.WEB_APP_URL
        val tab = data["tab"]

        val intent = Intent(this, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            this.data = Uri.parse(targetUrl)
            if (!tab.isNullOrBlank()) {
                putExtra("tab", tab)
            }
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }

        val pendingIntent = PendingIntent.getActivity(
            this,
            System.currentTimeMillis().toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val channelId = data["channelId"] ?: ScholarPulseApp.CHANNEL_ID_ANNOUNCEMENTS

        val largeIconBitmap = try {
            val drawable = ContextCompat.getDrawable(this, R.drawable.ic_splash_logo)
            if (drawable != null) {
                val size = (64 * resources.displayMetrics.density).toInt()
                val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bmp)
                drawable.setBounds(0, 0, canvas.width, canvas.height)
                drawable.draw(canvas)
                bmp
            } else null
        } catch (_: Exception) {
            null
        }

        val builder = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.ic_stat_scholarpulse)
            .setColor(ContextCompat.getColor(this, R.color.blue_600))
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)

        if (largeIconBitmap != null) {
            builder.setLargeIcon(largeIconBitmap)
        }

        notificationManager.notify(System.currentTimeMillis().toInt(), builder.build())
    }
}
