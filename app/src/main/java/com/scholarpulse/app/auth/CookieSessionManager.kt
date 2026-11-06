package com.scholarpulse.app.auth

import android.content.Context
import android.util.Log
import android.webkit.CookieManager
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import com.scholarpulse.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class CookieSessionManager(private val context: Context) {
    private val tag = "CookieSessionManager"
    private val cookieManager: CookieManager = CookieManager.getInstance()

    fun syncSessionCookie(sessionToken: String, domain: String? = null, rawHeader: String? = null) {
        val targetDomain = domain ?: BuildConfig.WEB_APP_URL
        val isSecure = targetDomain.startsWith("https://")

        cookieManager.setAcceptCookie(true)

        if (!rawHeader.isNullOrBlank()) {
            try {
                cookieManager.setCookie(targetDomain, rawHeader)
                cookieManager.setCookie("https://scholarpulsev2.vercel.app", rawHeader)
            } catch (e: Exception) {
                Log.w(tag, "Failed to set raw cookie header", e)
            }
        }

        val cookieValue = buildString {
            append("sp_session=$sessionToken; Path=/; ")
            if (isSecure) append("Secure; ")
            append("HttpOnly; SameSite=Lax; Max-Age=2592000")
        }

        try {
            cookieManager.setCookie(targetDomain, cookieValue)

            // Ensure host-wide coverage if vercel.app
            if (targetDomain.contains("scholarpulsev2.vercel.app")) {
                cookieManager.setCookie("https://scholarpulsev2.vercel.app", cookieValue)
            }

            // In local debug mode, sync to emulator host
            if (BuildConfig.DEBUG) {
                val debugCookie = "sp_session=$sessionToken; Path=/; HttpOnly; SameSite=Lax; Max-Age=2592000"
                cookieManager.setCookie("http://10.0.2.2:3000", debugCookie)
                cookieManager.setCookie("http://localhost:3000", debugCookie)
            }

            cookieManager.flush()
            Log.d(tag, "Synchronized sp_session cookie for target: $targetDomain")
        } catch (e: Exception) {
            Log.e(tag, "Failed to synchronize sp_session cookie", e)
        }
    }

    fun hasSessionCookie(domain: String? = null): Boolean {
        val targetDomain = domain ?: BuildConfig.WEB_APP_URL
        val cookies = cookieManager.getCookie(targetDomain) ?: return false
        return cookies.contains("sp_session=")
    }

    fun getSessionToken(domain: String? = null): String? {
        val targetDomain = domain ?: BuildConfig.WEB_APP_URL
        val cookies = cookieManager.getCookie(targetDomain) ?: return null
        val match = Regex("""(?:^|;\s*)sp_session=([^;]+)""").find(cookies)
        return match?.groupValues?.get(1)
    }

    /**
     * Decodes the JWT session token payload to extract the student's unique userId / uid.
     */
    fun getUserIdFromSessionToken(sessionToken: String? = null): String? {
        val token = sessionToken ?: getSessionToken() ?: return null
        return try {
            val parts = token.split(".")
            if (parts.size >= 2) {
                val payloadBytes = android.util.Base64.decode(
                    parts[1],
                    android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP
                )
                val payloadJson = String(payloadBytes, Charsets.UTF_8)
                val regex = Regex(""""(?:uid|sub|userId)"\s*:\s*"([^"]+)"""")
                regex.find(payloadJson)?.groupValues?.get(1)
            } else null
        } catch (e: Exception) {
            Log.w(tag, "Failed to parse userId from session JWT", e)
            null
        }
    }

    suspend fun clearSession() = withContext(Dispatchers.IO) {
        try {
            withContext(Dispatchers.Main) {
                cookieManager.removeAllCookies(null)
                cookieManager.flush()
            }
            val credentialManager = CredentialManager.create(context)
            credentialManager.clearCredentialState(ClearCredentialStateRequest())
            Log.d(tag, "Session successfully cleared: cookies removed and CredentialManager cleared")
        } catch (e: Exception) {
            Log.w(tag, "Exception during clearSession", e)
        }
    }
}
