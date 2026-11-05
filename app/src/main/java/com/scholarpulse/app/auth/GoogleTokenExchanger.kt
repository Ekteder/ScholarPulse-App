package com.scholarpulse.app.auth

import android.os.Build
import android.util.Log
import com.scholarpulse.app.BuildConfig
import com.scholarpulse.app.bridge.BridgeErrorCodes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

class GoogleTokenExchanger(
    private val primaryBaseUrl: String = BuildConfig.WEB_APP_URL
) {
    private val tag = "GoogleTokenExchanger"
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    suspend fun exchangeToken(
        idToken: String,
        deviceId: String?,
        deviceModel: String = Build.MODEL,
        appVersion: String = BuildConfig.VERSION_NAME,
        fcmToken: String? = null
    ): TokenExchangeResult = withContext(Dispatchers.IO) {
        val endpointUrl = "${primaryBaseUrl.trimEnd('/')}/api/auth/google-native"
        
        // Build payload omitting nulls so Zod optional fields on backend do not reject with validation error
        val payloadJson = buildJsonObject {
            put("idToken", idToken)
            if (!deviceId.isNullOrBlank()) {
                put("deviceId", deviceId)
            }
            if (deviceModel.isNotBlank()) {
                put("deviceModel", deviceModel)
            }
            if (appVersion.isNotBlank()) {
                put("appVersion", appVersion)
            }
            if (!fcmToken.isNullOrBlank()) {
                put("fcmToken", fcmToken)
            }
        }.toString()

        try {
            executePostRequest(endpointUrl, payloadJson)
        } catch (e: Exception) {
            Log.e(tag, "Primary auth exchange failed on $endpointUrl: ${e.message}", e)
            if (BuildConfig.DEBUG && !primaryBaseUrl.contains("10.0.2.2")) {
                val fallbackUrl = "http://10.0.2.2:3000/api/auth/google-native"
                Log.i(tag, "Attempting fallback auth exchange on $fallbackUrl")
                try {
                    return@withContext executePostRequest(fallbackUrl, payloadJson)
                } catch (fallbackEx: Exception) {
                    Log.e(tag, "Fallback auth exchange also failed: ${fallbackEx.message}", fallbackEx)
                }
            }
            TokenExchangeResult.Failure(
                errorCode = BridgeErrorCodes.E_AUTH_FAILED,
                message = "Token exchange network error: ${e.message}",
                cause = e
            )
        }
    }

    private fun executePostRequest(
        urlString: String,
        payloadJson: String
    ): TokenExchangeResult {
        val url = URL(urlString)
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15000
            readTimeout = 15000
            doInput = true
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "ScholarPulseAndroid/${BuildConfig.VERSION_NAME}")
        }

        OutputStreamWriter(connection.outputStream, Charsets.UTF_8).use { writer ->
            writer.write(payloadJson)
            writer.flush()
        }

        val responseCode = connection.responseCode
        val setCookieHeader = connection.headerFields["Set-Cookie"]?.joinToString("; ")
            ?: connection.getHeaderField("Set-Cookie")

        return if (responseCode in 200..299) {
            val responseText = BufferedReader(InputStreamReader(connection.inputStream, Charsets.UTF_8)).use {
                it.readText()
            }
            connection.disconnect()

            try {
                val parsed = json.decodeFromString<GoogleNativeAuthResponse>(responseText)
                if (parsed.success && !parsed.sessionToken.isNullOrBlank()) {
                    TokenExchangeResult.Success(
                        sessionToken = parsed.sessionToken,
                        user = parsed.user,
                        rawCookieHeader = setCookieHeader
                    )
                } else {
                    val errMsg = parsed.error?.message ?: "Backend rejected token authentication"
                    Log.w(tag, "Backend returned success=false: $errMsg")
                    TokenExchangeResult.Failure(
                        errorCode = BridgeErrorCodes.E_AUTH_FAILED,
                        message = errMsg,
                        httpStatusCode = responseCode
                    )
                }
            } catch (e: Exception) {
                Log.e(tag, "Failed to parse backend JSON response: $responseText", e)
                TokenExchangeResult.Failure(
                    errorCode = BridgeErrorCodes.E_AUTH_FAILED,
                    message = "Malformed response from auth server: ${e.message}",
                    httpStatusCode = responseCode,
                    cause = e
                )
            }
        } else {
            val errorStream = connection.errorStream
            val errorResponseText = if (errorStream != null) {
                BufferedReader(InputStreamReader(errorStream, Charsets.UTF_8)).use { it.readText() }
            } else ""
            connection.disconnect()

            val errorDetail = try {
                if (errorResponseText.isNotBlank()) {
                    json.decodeFromString<GoogleNativeAuthResponse>(errorResponseText).error?.message
                } else null
            } catch (_: Exception) {
                null
            }

            val finalMsg = errorDetail ?: "Server responded with HTTP $responseCode: $errorResponseText"
            Log.e(tag, "HTTP $responseCode from $urlString: $finalMsg")

            TokenExchangeResult.Failure(
                errorCode = BridgeErrorCodes.E_AUTH_FAILED,
                message = finalMsg,
                httpStatusCode = responseCode
            )
        }
    }
}
