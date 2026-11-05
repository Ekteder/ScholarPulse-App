package com.scholarpulse.app.auth

import android.content.Context
import android.util.Log
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetCredentialResponse
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenParsingException
import com.scholarpulse.app.BuildConfig
import com.scholarpulse.app.bridge.BridgeErrorCodes

class CredentialManagerHelper(
    private val context: Context,
    private val serverClientId: String = BuildConfig.GOOGLE_SERVER_CLIENT_ID
) {
    private val tag = "CredentialManagerHelper"
    // Use applicationContext so the manager survives Activity transitions
    private val credentialManager = CredentialManager.create(context.applicationContext)

    suspend fun signIn(activityContext: Context): GoogleSignInResult {
        return try {
            Log.e(tag, "STEP 1: Building GetSignInWithGoogleOption with serverClientId: $serverClientId")
            val option = GetSignInWithGoogleOption.Builder(
                serverClientId = serverClientId
            ).build()

            val request = GetCredentialRequest.Builder()
                .addCredentialOption(option)
                .build()

            Log.e(tag, "STEP 2: Calling credentialManager.getCredential using Activity: $activityContext")
            val response: GetCredentialResponse = credentialManager.getCredential(
                context = activityContext,
                request = request
            )

            Log.e(tag, "STEP 3: Received GetCredentialResponse! Credential type: ${response.credential.type}")
            handleCredentialResponse(response)
        } catch (ce: GetCredentialCancellationException) {
            Log.e(tag, "STEP 3 CANCELLED: User dismissed or cancelled Google Sign-In sheet: ${ce.message}", ce)
            GoogleSignInResult.Failure(
                errorCode = BridgeErrorCodes.E_AUTH_CANCELLED,
                message = "User dismissed Google sign-in dialog",
                cause = ce
            )
        } catch (ne: NoCredentialException) {
            Log.e(tag, "STEP 3 NO_CREDENTIAL: No credentials available: ${ne.message}", ne)
            GoogleSignInResult.Failure(
                errorCode = BridgeErrorCodes.E_AUTH_FAILED,
                message = "No Google credentials available on this device: ${ne.message}",
                cause = ne
            )
        } catch (ge: GetCredentialException) {
            Log.e(tag, "STEP 3 GET_CREDENTIAL_EXCEPTION: ${ge.type} - ${ge.message}", ge)
            GoogleSignInResult.Failure(
                errorCode = BridgeErrorCodes.E_AUTH_FAILED,
                message = ge.message ?: "Sign-in failed (${ge.type})",
                cause = ge
            )
        } catch (e: Exception) {
            Log.e(tag, "STEP 3 UNEXPECTED_EXCEPTION: ${e.javaClass.name} - ${e.message}", e)
            GoogleSignInResult.Failure(
                errorCode = BridgeErrorCodes.E_AUTH_FAILED,
                message = e.message ?: "An unexpected error occurred during Google Sign-In",
                cause = e
            )
        }
    }

    private fun handleCredentialResponse(response: GetCredentialResponse): GoogleSignInResult {
        val credential = response.credential

        if (credential is CustomCredential &&
            credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
        ) {
            return try {
                val googleIdTokenCredential = GoogleIdTokenCredential.createFrom(credential.data)
                val idToken = googleIdTokenCredential.idToken
                if (idToken.isBlank()) {
                    Log.e(tag, "Google ID token extracted from credential is blank")
                    GoogleSignInResult.Failure(
                        errorCode = BridgeErrorCodes.E_AUTH_FAILED,
                        message = "Google ID token extracted from credential is empty"
                    )
                } else {
                    Log.e(tag, "STEP 4 SUCCESS: Successfully extracted Google ID token for: ${googleIdTokenCredential.id}")
                    GoogleSignInResult.Success(
                        idToken = idToken,
                        email = googleIdTokenCredential.id,
                        displayName = googleIdTokenCredential.displayName,
                        profilePictureUri = googleIdTokenCredential.profilePictureUri?.toString()
                    )
                }
            } catch (pe: GoogleIdTokenParsingException) {
                Log.e(tag, "Failed to parse Google ID token credential", pe)
                GoogleSignInResult.Failure(
                    errorCode = BridgeErrorCodes.E_AUTH_FAILED,
                    message = "Failed to parse Google ID token: ${pe.message}",
                    cause = pe
                )
            }
        }

        Log.e(tag, "Unexpected credential type returned: ${credential.type}")
        return GoogleSignInResult.Failure(
            errorCode = BridgeErrorCodes.E_AUTH_FAILED,
            message = "Unrecognized credential type: ${credential.type}"
        )
    }

    suspend fun clearCredentialState() {
        try {
            credentialManager.clearCredentialState(ClearCredentialStateRequest())
            Log.d(tag, "Credential state successfully cleared")
        } catch (e: Exception) {
            Log.w(tag, "Failed to clear credential state", e)
        }
    }
}
