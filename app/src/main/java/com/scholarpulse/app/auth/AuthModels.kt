package com.scholarpulse.app.auth

import kotlinx.serialization.Serializable

@Serializable
data class GoogleNativeAuthRequest(
    val idToken: String,
    val deviceId: String? = null,
    val deviceModel: String? = null,
    val appVersion: String? = null,
    val fcmToken: String? = null
)

@Serializable
data class AuthUser(
    val uid: String,
    val email: String,
    val name: String,
    val avatarUrl: String? = null,
    val onboardingCompleted: Boolean = false
)

@Serializable
data class GoogleNativeAuthResponse(
    val success: Boolean,
    val sessionToken: String? = null,
    val user: AuthUser? = null,
    val error: AuthErrorDetail? = null
)

@Serializable
data class AuthErrorDetail(
    val code: String? = null,
    val message: String? = null
)

@Serializable
data class GoogleSignInResponseDto(
    val authenticated: Boolean,
    val sessionToken: String? = null,
    val user: AuthUser? = null
)

@Serializable
data class SyncSessionParams(
    val token: String? = null,
    val sessionToken: String? = null,
    val domain: String? = null
) {
    fun getEffectiveToken(): String? = (token ?: sessionToken)?.takeIf { it.isNotBlank() }
}

sealed class GoogleSignInResult {
    data class Success(
        val idToken: String,
        val email: String? = null,
        val displayName: String? = null,
        val profilePictureUri: String? = null
    ) : GoogleSignInResult()

    data class Failure(
        val errorCode: String,
        val message: String,
        val cause: Throwable? = null
    ) : GoogleSignInResult()
}

sealed class TokenExchangeResult {
    data class Success(
        val sessionToken: String,
        val user: AuthUser?,
        val rawCookieHeader: String? = null
    ) : TokenExchangeResult()

    data class Failure(
        val errorCode: String,
        val message: String,
        val httpStatusCode: Int? = null,
        val cause: Throwable? = null
    ) : TokenExchangeResult()
}
