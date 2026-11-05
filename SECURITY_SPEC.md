# ScholarPulse V2: Mobile & Hybrid Security Architecture Plan
**Target Shell**: Android Native Container (`/media/sanglap/Movies/scholarpulsev2/app`)  
**Web Core**: Next.js 16.4.3 App Router (`/media/sanglap/Movies/scholarpulsev2/v2`)  
**Lead Security Author**: Application Security Specialist (`security-engineer`)  
**Operating Mode**: PLANNING & ARCHITECTURAL SPECIFICATION  
**Status**: APPROVED FOR IMPLEMENTATION  
**Date**: November 4, 2026  

---

## 1. Executive Summary & Codebase Security Scan (v2)

An empirical static analysis and security audit was conducted on `/media/sanglap/Movies/scholarpulsev2/v2`. The audit scrutinized authentication flows, session lifecycles, database access layers, and server actions to establish the baseline upon which the native Android shell must interface.

### 1.1 Web Application Audit Findings (v2)

#### 1. Authentication Engine (`v2/src/lib/auth.ts`)
- **JWT Cryptographic Signing**: Tokens are minted using Jose (`SignJWT`) with algorithm **`HS256`** and a symmetric secret key (`AUTH_SECRET`). Environment validation correctly fails fast on missing or sub-32-byte secret keys.
- **Session Cookie Attributes**: `sp_session` is configured with `httpOnly: true`, `secure: process.env.NODE_ENV === 'production'`, `sameSite: 'lax'`, `path: '/'`, and `maxAge: 30 * 24 * 60 * 60` (30 days).
- **VULN-01 (High): Fail-Open Ban Check in `getCurrentUser()`**:
  - In `v2/src/lib/auth.ts:165-177`, when querying `profiles.isBanned`, errors (e.g. transient Postgres pool saturation) are caught in an empty `try/catch`, falling back to `isBanned = false`. This violates fail-closed security standards.
- **VULN-02 (Medium): Cryptographic Verification Missing in ID Token Fallback**:
  - In `exchangeGoogleCodeForUser()`, if `googleapis.com/oauth2/v3/userinfo` fails, the code decodes `id_token` payload directly from base64 (`Buffer.from(parts[1], 'base64')`) without validating the Google signature against Google JWKS.
- **VULN-03 (High): Missing CSRF State in Server Action `getGoogleOAuthUrlAction`**:
  - In `v2/src/actions/auth.ts:9-12`, `getGoogleOAuthUrlAction(origin)` generates an OAuth URL with arbitrary client-supplied origin and **no state parameter**, bypassing the `oauth_state` cookie verification implemented in `/api/auth/google`.

#### 2. Route Handlers & Realtime SSE (`v2/src/app/api`)
- **OAuth Callback (`/api/auth/google/callback`)**: Enforces state matching against `oauth_state` cookie. However, `getTrustedBaseUrl` relies on `x-forwarded-host` headers in development environments, which can be poisoned if proxies are unpinned.
- **Realtime SSE (`/api/realtime`)**: Validates session via `getCurrentUser()` and enforces ban status before opening the readable stream. Dual-channel dispatch (in-memory EventEmitter + Neon DB polling with adaptive backoff from 2000ms to 4000ms) prevents polling stampedes.

#### 3. Server Actions & IDOR/BOLA Protection (`v2/src/actions`)
- **Proper IDOR Mitigation**: Core actions (`timer.ts`, `tasks.ts`, `sessions.ts`, `workspace.ts`) strictly resolve the calling user via `requireUser()` and scope mutations to `userId` (e.g. `and(eq(tasks.id, id), eq(tasks.userId, user.uid))`).
- **VULN-04 (Medium): Missing Resource URL Scheme Validation & Stored XSS**:
  - In `v2/src/lib/validations/index.ts:163-171` (`CurriculumResourceSchema`), `url` is validated only as `z.string().min(1).max(1000)`. In `v2/src/components/RevisionTrackerView.tsx:1673-1676`, `res.url` is directly bound to `<a href={res.url}>`.
  - A payload such as `javascript:alert(document.cookie)` constitutes Stored XSS in the browser and arbitrary JavaScript execution inside the Android WebView.
- **VULN-05 (High): Next.js Middleware Bypass & Missing Security Headers**:
  - Security headers are located in `v2/src/proxy.ts`, but Next.js App Router requires `src/middleware.ts` or root `middleware.ts`. Consequently, `proxy.ts` is never executed by Next.js. `next.config.ts` currently defines zero Content-Security-Policy (CSP) or Strict-Transport-Security headers.

---

## 2. Android WebView + Native Shell Security Threat Model

The hybrid architecture embeds the responsive Next.js web application inside an Android native shell written in Kotlin and Jetpack Compose.

### STRIDE Threat Matrix for ScholarPulse Hybrid Shell

| Threat ID | Threat Category (STRIDE) | Attack Vector / Exploit Scenario | Severity (CVSS v3.1) | Impact | Architectural Mitigation |
|---|---|---|---|---|---|
| **THREAT-01** | **Elevation of Privilege / Auth Bypass** | Google OAuth fails with `403 disallowed_useragent` when loaded in WebView; insecure bridge exposes auth tokens to arbitrary JavaScript. | **CRITICAL**<br>9.1 (CVSS:3.1/AV:N/AC:L/PR:N/UI:N/S:U/C:H/I:H/A:N) | Complete auth failure; token hijacking if exposed to JS memory. | Native Google Sign-In via `androidx.credentials.CredentialManager`. Token exchange on hardened backend endpoint `/api/auth/native-google`. Cookie sync via native `CookieManager.setCookie` directly into HTTP cookie store. |
| **THREAT-02** | **Tampering / Information Disclosure** | WebView allows local file access (`file://`), enabling an attacker to read app private SQLite databases, SharedPrefs, or Keystore blobs via XSS. | **HIGH**<br>8.2 (CVSS:3.1/AV:N/AC:L/PR:N/UI:R/S:C/C:H/I:L/A:N) | Exfiltration of user private study logs, cached profile data, and session tokens. | Explicitly disable `allowFileAccess`, `allowContentAccess`, `allowFileAccessFromFileURLs`, `allowUniversalAccessFromFileURLs`. Serve offline assets via `WebViewAssetLoader`. |
| **THREAT-03** | **Information Disclosure (MitM)** | Application bypasses SSL certificate validation (`handler.proceed()` in `onReceivedSslError`) or loads insecure HTTP resources. | **CRITICAL**<br>8.6 (CVSS:3.1/AV:N/AC:H/PR:N/UI:N/S:C/C:H/I:H/A:N) | Interception of session cookies, credentials, and real-time SSE stream over public Wi-Fi. | Explicit `handler?.cancel()` with zero proceed calls. Enforce `MIXED_CONTENT_NEVER_ALLOW`. Enforce TLS 1.3 via `network_security_config.xml`. |
| **THREAT-04** | **Elevation of Privilege / Code Execution** | Legacy `@JavascriptInterface` bridge is callable by unauthorized third-party iframes or hijacked web views without origin checks. | **HIGH**<br>8.5 (CVSS:3.1/AV:N/AC:L/PR:L/UI:N/S:C/C:H/I:L/A:N) | Malicious web frames trigger native Android services, vibration, audio, or steal device telemetry. | Migrate bridge from `addJavascriptInterface` to modern `WebViewCompat.addWebMessageListener` with strict origin allowlist. Validate JSON schema. Hop to `Dispatchers.Main`. |
| **THREAT-05** | **Spoofing / Deep Link Hijacking** | Malicious third-party Android application registers intent filters for `https://scholarpulsev2.vercel.app` or custom scheme, intercepting auth tokens or user redirects. | **MEDIUM**<br>6.8 (CVSS:3.1/AV:N/AC:H/PR:N/UI:R/S:U/C:H/I:L/A:N) | Sensitive links and notifications intercepted by competitor or rogue app on the same device. | Enforce Android App Links with `android:autoVerify="true"`. Cryptographically pin app signing SHA-256 fingerprint in `/.well-known/assetlinks.json`. |
| **THREAT-06** | **Information Disclosure / Secrets Hygiene** | FCM tokens, offline timer state, or device identifiers stored in plaintext SharedPreferences or world-readable storage. | **MEDIUM**<br>6.2 (CVSS:3.1/AV:L/AC:L/PR:N/UI:N/S:U/C:H/I:N/A:N) | Malicious backup extraction or rooted device malware steals persistent user identity. | Android KeyStore-backed AES-256-GCM encryption with Jetpack DataStore. Disable `android:allowBackup` and enforce `data_extraction_rules.xml`. |
| **THREAT-07** | **Denial of Service / UI Freeze** | Bridge calls invoked rapidly from JavaScript freeze the Android UI thread due to synchronous blocking operations or deadlock. | **MEDIUM**<br>5.3 (CVSS:3.1/AV:N/AC:L/PR:N/UI:N/S:U/C:N/I:N/A:L) | App ANR (Application Not Responding) crash forcing user to force-close. | Asynchronous coroutine dispatching: isolate UI updates to `Dispatchers.Main` and I/O to `Dispatchers.IO`. In-bridge rate limiter (max 20 calls/sec). |

---

## 3. Hardened WebView Architectural Specifications

### 3.1 Security Baseline Configuration
The Android WebView must be created with defensive defaults in `SecureWebViewFactory.kt`:

```kotlin
package com.scholarpulse.app.webview

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.WebSettings
import android.webkit.WebView
import com.scholarpulse.app.BuildConfig

object SecureWebViewFactory {
    @SuppressLint("SetJavaScriptEnabled")
    fun create(context: Context): WebView {
        return WebView(context).apply {
            settings.apply {
                // 1. JavaScript is strictly required for Next.js App Router
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = false // Deprecated; prevent Web SQL injection

                // 2. Strict file & content scheme isolation (Mitigates THREAT-02)
                allowFileAccess = false
                allowContentAccess = false
                allowFileAccessFromFileURLs = false
                allowUniversalAccessFromFileURLs = false

                // 3. Network & Transport Hygiene (Mitigates THREAT-03)
                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                cacheMode = WebSettings.LOAD_DEFAULT

                // 4. Geolocation & Hardware lockdown
                setGeolocationEnabled(false)

                // 5. User-Agent customization for responsive layout negotiation
                userAgentString = "${userAgentString} ScholarPulseNativeShell/1.0"
            }

            // 6. Disable WebView debugging in production release builds
            WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
        }
    }
}
```

### 3.2 SSL Verification & Navigation Lockdown (`SecureWebViewClient`)
Never bypass certificate errors under any circumstance. Enforce host allowlisting:

```kotlin
package com.scholarpulse.app.webview

import android.net.Uri
import android.net.http.SslError
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewAssetLoader

class SecureWebViewClient(
    private val allowedHost: String,
    private val onExternalUriRequested: (Uri) -> Unit,
    private val onSslErrorEncountered: (SslError?) -> Unit,
    private val onRenderProcessCrashed: () -> Unit,
    assetLoader: WebViewAssetLoader
) : WebViewClient() {

    private val localAssetLoader = assetLoader

    override fun shouldInterceptRequest(
        view: WebView?,
        request: WebResourceRequest?
    ): WebResourceResponse? {
        // Intercept virtual domain https://appassets.androidplatform.net/ for offline fallback
        return request?.url?.let { localAssetLoader.shouldInterceptRequest(it) }
    }

    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
        val uri = request?.url ?: return true
        val scheme = uri.scheme?.lowercase() ?: return true

        // 1. Intercept external protocols (tel, mailto, intent)
        if (scheme == "mailto" || scheme == "tel" || scheme == "intent") {
            onExternalUriRequested(uri)
            return true
        }

        // 2. Enforce HTTPS only
        if (scheme != "https") {
            return true // Drop insecure schemes (http, ftp, file)
        }

        // 3. Validate Host Allowlist
        val host = uri.host?.lowercase() ?: return true
        if (host == allowedHost || host.endsWith(".$allowedHost")) {
            return false // Allow navigation within ScholarPulse domain
        }

        // 4. Untrusted external domain -> launch via Chrome Custom Tabs / external browser
        onExternalUriRequested(uri)
        return true
    }

    override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) {
        // CRITICAL VULNERABILITY PREVENTION:
        // Calling handler?.proceed() triggers instant Google Play rejection and critical MitM vulnerability.
        handler?.cancel()
        onSslErrorEncountered(error)
    }

    override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
        // Prevent host app crash if Chromium sandbox render process exhausts memory
        onRenderProcessCrashed()
        return true
    }
}
```

---

## 4. Hardened JavaScript Bridge Architecture

### 4.1 Modern Origin-Restricted Bridge (`WebViewCompat.addWebMessageListener`)
Instead of legacy `@JavascriptInterface` which injects into all frames, ScholarPulse adopts `WebViewCompat.addWebMessageListener` supported by `androidx.webkit:webkit:1.12.0+`.

```kotlin
package com.scholarpulse.app.bridge

import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class NativeMessageBridge(
    private val scope: CoroutineScope,
    private val allowedOrigins: Set<String>,
    private val messageDispatcher: BridgeActionDispatcher
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val json = Json { ignoreUnknownKeys = true }

    fun attach(webView: WebView) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            throw IllegalStateException("WebMessageListener feature not supported on this Android WebView version.")
        }

        WebViewCompat.addWebMessageListener(
            webView,
            "scholarPulseBridge", // Exposed as window.scholarPulseBridge in DOM
            allowedOrigins
        ) { view, message, sourceOrigin, isMainFrame, replyProxy ->
            handleIncomingMessage(message, sourceOrigin, isMainFrame, replyProxy)
        }
    }

    private fun handleIncomingMessage(
        message: WebMessageCompat,
        sourceOrigin: Uri,
        isMainFrame: Boolean,
        replyProxy: JavaScriptReplyProxy
    ) {
        // 1. Verify frame origin
        val originStr = sourceOrigin.toString()
        if (!allowedOrigins.contains(originStr)) {
            sendErrorReply(replyProxy, "UNAUTHORIZED_ORIGIN", "Origin $originStr is not allowed.")
            return
        }

        // 2. Validate top-level frame execution
        if (!isMainFrame) {
            sendErrorReply(replyProxy, "FRAME_REJECTED", "Bridge actions permitted only from main frame.")
            return
        }

        val rawData = message.data ?: return

        // 3. Asynchronously parse and dispatch
        scope.launch(Dispatchers.Default) {
            try {
                val envelope = json.parseToJsonElement(rawData).jsonObject
                val action = envelope["action"]?.jsonPrimitive?.content ?: throw IllegalArgumentException("Missing action")
                val requestId = envelope["requestId"]?.jsonPrimitive?.content ?: ""

                messageDispatcher.dispatch(action, envelope["payload"], replyProxy, requestId)
            } catch (e: Exception) {
                sendErrorReply(replyProxy, "INVALID_PAYLOAD", e.message ?: "Payload parsing failed.")
            }
        }
    }

    private fun sendErrorReply(replyProxy: JavaScriptReplyProxy, code: String, message: String) {
        mainHandler.post {
            replyProxy.postMessage("""{"status":"error","code":"$code","message":"$message"}""")
        }
    }
}
```

### 4.2 Web Client Bridge Contract (`TypeScript`)
```typescript
export interface BridgeRequest<T = unknown> {
  requestId: string;
  action: 'START_TIMER' | 'STOP_TIMER' | 'PLAY_CHIME' | 'SYNC_DEVICE_TELEMETRY' | 'REQUEST_NOTIFICATIONS';
  payload: T;
  timestamp: number;
}

export interface BridgeResponse<R = unknown> {
  requestId: string;
  status: 'success' | 'error';
  code?: string;
  data?: R;
  error?: string;
}

export function sendNativeMessage<T, R>(action: BridgeRequest<T>['action'], payload: T): Promise<R> {
  return new Promise((resolve, reject) => {
    if (typeof window === 'undefined' || !(window as any).scholarPulseBridge) {
      return reject(new Error('Native bridge not available'));
    }

    const requestId = crypto.randomUUID();
    const request: BridgeRequest<T> = {
      requestId,
      action,
      payload,
      timestamp: Date.now(),
    };

    const handleReply = (event: MessageEvent) => {
      try {
        const response: BridgeResponse<R> = JSON.parse(event.data);
        if (response.requestId === requestId) {
          window.removeEventListener('message', handleReply);
          if (response.status === 'success' && response.data !== undefined) {
            resolve(response.data);
          } else {
            reject(new Error(response.error || 'Native bridge operation failed'));
          }
        }
      } catch {}
    };

    window.addEventListener('message', handleReply);
    (window as any).scholarPulseBridge.postMessage(JSON.stringify(request));

    // 5-second timeout safeguard
    setTimeout(() => {
      window.removeEventListener('message', handleReply);
      reject(new Error(`Native bridge call timeout for action: ${action}`));
    }, 5000);
  });
}
```

---

## 5. Native Authentication & Session Cookie Synchronization

Google prohibits OAuth authentication inside WebViews (`disallowed_useragent`). ScholarPulse resolves this by orchestrating **Native Android CredentialManager** with server-side validation and atomic session cookie injection.

### 5.1 Native Google Sign-In Flow Architecture

```mermaid
sequenceDiagram
    autonumber
    actor Student as Student (Android App)
    participant Shell as Android Native Shell (CredentialManager)
    participant Google as Google Identity Services
    participant Backend as ScholarPulse API (/api/auth/native-google)
    participant CookieJar as Android CookieManager
    participant WebView as Android WebView (Next.js App)

    Student->>Shell: Tap "Sign in with Google"
    Shell->>Google: GetCredentialRequest(GoogleIdOption, serverClientId)
    Google-->>Shell: GoogleIdTokenCredential (OIDC JWT)
    Shell->>Backend: POST /api/auth/native-google { idToken: "ey..." }
    Note over Backend: 1. Verify Signature with Google JWKS<br/>2. Validate aud == GOOGLE_CLIENT_ID<br/>3. Validate iss & exp<br/>4. Lookup/Create Profile in Neon DB<br/>5. Mint HS256 sp_session JWT
    Backend-->>Shell: HTTP 200 OK + Set-Cookie: sp_session=...; HttpOnly; Secure; SameSite=Lax
    Shell->>CookieJar: CookieManager.setCookie("https://scholarpulsev2.vercel.app", cookieString)
    Shell->>CookieJar: CookieManager.flush()
    Shell->>WebView: loadUrl("https://scholarpulsev2.vercel.app/")
    WebView->>Backend: GET / (Includes HttpOnly sp_session cookie)
    Backend-->>WebView: Render Authenticated Student Workspace
```

### 5.2 Server-Side ID Token Verification Endpoint (Contract & Logic)
Hardened route handler `/api/auth/native-google/route.ts` for `v2`:

```typescript
import { NextRequest, NextResponse } from 'next/server';
import { createRemoteJWKSet, jwtVerify } from 'jose';
import { db } from '@/lib/db';
import { profiles } from '@/lib/db/schema';
import { eq } from 'drizzle-orm';
import { createSession } from '@/lib/auth';

const GOOGLE_JWKS_URL = new URL('https://www.googleapis.com/oauth2/v3/certs');
const googleJWKS = createRemoteJWKSet(GOOGLE_JWKS_URL);

export async function POST(req: NextRequest) {
  try {
    const body = await req.json();
    const idToken = body?.idToken;

    if (!idToken || typeof idToken !== 'string') {
      return NextResponse.json({ error: 'Missing idToken parameter' }, { status: 400 });
    }

    const expectedAudience = process.env.GOOGLE_CLIENT_ID;
    if (!expectedAudience) {
      throw new Error('Fatal: GOOGLE_CLIENT_ID environment variable is missing.');
    }

    // 1. Cryptographically verify Google OIDC token signature and claims
    const { payload } = await jwtVerify(idToken, googleJWKS, {
      issuer: ['https://accounts.google.com', 'accounts.google.com'],
      audience: expectedAudience,
    });

    const sub = payload.sub;
    const email = payload.email as string;
    const name = (payload.name as string) || 'Student Scholar';
    const picture = (payload.picture as string) || '';
    const emailVerified = payload.email_verified as boolean;

    if (!sub || !email || !emailVerified) {
      return NextResponse.json({ error: 'Unverified Google account' }, { status: 403 });
    }

    // 2. Ensure user profile exists in Neon PostgreSQL
    const existing = await db
      .select({ userId: profiles.userId, isBanned: profiles.isBanned })
      .from(profiles)
      .where(eq(profiles.userId, sub))
      .limit(1);

    if (existing.length > 0 && existing[0].isBanned) {
      return NextResponse.json({ error: 'Account is suspended' }, { status: 403 });
    }

    if (existing.length === 0) {
      await db.insert(profiles).values({
        userId: sub,
        email,
        fullName: name,
        avatarUrl: picture,
        dailyStudyTargetMinutes: 360,
        preferredStudyTime: 'evening',
        timezone: 'Asia/Dhaka',
        themePreference: 'light',
        pomodoroFocusMinutes: 25,
        pomodoroShortBreakMinutes: 5,
        pomodoroLongBreakMinutes: 15,
        pomodoroLongBreakInterval: 4,
        onboardingCompleted: false,
        targetExamName: 'HSC 2027',
        targetExamDate: '2027-06-01',
      });
    }

    // 3. Mint standard sp_session cookie
    await createSession({
      uid: sub,
      email,
      name,
      avatarUrl: picture,
    });

    return NextResponse.json({
      success: true,
      user: { uid: sub, email, name, avatarUrl: picture },
    });
  } catch (err: any) {
    console.error('Native Google authentication failed:', err);
    return NextResponse.json({ error: 'Authentication failed' }, { status: 401 });
  }
}
```

### 5.3 Native Cookie Synchronization Implementation
```kotlin
package com.scholarpulse.app.auth

import android.webkit.CookieManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Response

class SessionCookieSyncManager {

    suspend fun syncSessionFromResponse(domainUrl: String, response: Response): Boolean = withContext(Dispatchers.IO) {
        val cookieHeaders = response.headers("Set-Cookie")
        if (cookieHeaders.isEmpty()) return@withContext false

        val cookieManager = CookieManager.getInstance().apply {
            setAcceptCookie(true)
        }

        var sessionCookieFound = false
        for (cookieHeader in cookieHeaders) {
            if (cookieHeader.startsWith("sp_session=")) {
                sessionCookieFound = true
            }
            cookieManager.setCookie(domainUrl, cookieHeader)
        }

        // Flush memory cookies to disk storage immediately
        cookieManager.flush()
        return@withContext sessionCookieFound
    }

    suspend fun clearSessionCookies(domainUrl: String) = withContext(Dispatchers.IO) {
        val cookieManager = CookieManager.getInstance()
        cookieManager.setCookie(domainUrl, "sp_session=; Max-Age=0; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Path=/")
        cookieManager.flush()
    }
}
```

---

## 6. Android Keystore & Persistent Storage Hardening

`EncryptedSharedPreferences` is deprecated in modern Android development due to concurrency deadlocks and key corruption issues. ScholarPulse implements a secure repository using **Jetpack Preferences DataStore** encrypted with **Keystore-backed AES-256-GCM**.

### 6.1 Cryptographic Storage Engine
```kotlin
package com.scholarpulse.app.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

@OptIn(ExperimentalEncodingApi::class)
class KeystoreCryptoManager {

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "ScholarPulseMasterKey_v1"
        private const val GCM_IV_LENGTH = 12
        private const val GCM_TAG_LENGTH = 128
    }

    private val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    init {
        getOrCreateSecretKey()
    }

    private fun getOrCreateSecretKey(): SecretKey {
        if (!keyStore.containsAlias(KEY_ALIAS)) {
            val keyGenerator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES,
                ANDROID_KEYSTORE
            )
            val spec = KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build()

            keyGenerator.init(spec)
            keyGenerator.generateKey()
        }
        return (keyStore.getEntry(KEY_ALIAS, null) as KeyStore.SecretKeyEntry).secretKey
    }

    fun encrypt(plainText: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateSecretKey())
        val iv = cipher.iv
        val cipherText = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
        val combined = iv + cipherText
        return Base64.encode(combined)
    }

    fun decrypt(encryptedBase64: String): String {
        val combined = Base64.decode(encryptedBase64)
        if (combined.size < GCM_IV_LENGTH) throw IllegalArgumentException("Corrupted ciphertext")

        val iv = combined.copyOfRange(0, GCM_IV_LENGTH)
        val cipherText = combined.copyOfRange(GCM_IV_LENGTH, combined.size)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val spec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateSecretKey(), spec)

        val plainBytes = cipher.doFinal(cipherText)
        return String(plainBytes, Charsets.UTF_8)
    }
}
```

### 6.2 Network Security Config (`network_security_config.xml`)
Blocks cleartext HTTP, pins system CAs, and enforces modern TLS:

```xml
<?xml version="1.0" encoding="utf-8"?>
<network-security-config>
    <base-config cleartextTrafficPermitted="false">
        <trust-anchors>
            <certificates src="system" />
        </trust-anchors>
    </base-config>

    <domain-config cleartextTrafficPermitted="false">
        <domain includeSubdomains="true">scholarpulsev2.vercel.app</domain>
        <trust-anchors>
            <certificates src="system" />
        </trust-anchors>
    </domain-config>

    <!-- Local development overrides allowed strictly during debug builds -->
    <debug-overrides>
        <trust-anchors>
            <certificates src="system" />
            <certificates src="user" />
        </trust-anchors>
    </debug-overrides>
</network-security-config>
```

---

## 7. Deep Links & Digital Asset Links Verification

To prevent deep link hijacking (THREAT-05), Android App Links must enforce cryptographic verification via `assetlinks.json`.

### 7.1 AndroidManifest Intent Filter Declaration
```xml
<activity
    android:name=".ui.MainActivity"
    android:exported="true"
    android:launchMode="singleTask">

    <intent-filter>
        <action android:name="android.intent.action.MAIN" />
        <category android:name="android.intent.category.LAUNCHER" />
    </intent-filter>

    <!-- Cryptographically Verified App Links -->
    <intent-filter android:autoVerify="true">
        <action android:name="android.intent.action.VIEW" />
        <category android:name="android.intent.category.DEFAULT" />
        <category android:name="android.intent.category.BROWSABLE" />

        <data
            android:scheme="https"
            android:host="scholarpulsev2.vercel.app"
            android:pathPrefix="/" />
    </intent-filter>
</activity>
```

### 7.2 Digital Asset Links File (`assetlinks.json`)
Placed at `v2/public/.well-known/assetlinks.json`:

```json
[
  {
    "relation": ["delegate_permission/common.handle_all_urls"],
    "target": {
      "namespace": "android_app",
      "package_name": "com.scholarpulse.app",
      "sha256_cert_fingerprints": [
        "FA:C6:17:45:DC:09:03:78:6F:B9:ED:E6:2A:96:2B:39:9F:73:48:F0:BB:6F:89:9B:83:32:66:75:91:03:3B:9C"
      ]
    }
  }
]
```

---

## 8. Requirements for PRD & TRD

### 8.1 Product Requirements (PRD) — Security & Compliance
- **SEC-PRD-01 (Zero Cleartext Auth)**: Under no circumstance shall user credentials or session JWTs be transmitted in plaintext or exposed to client-side DOM storage (`localStorage`).
- **SEC-PRD-02 (Seamless Native Sign-In)**: The student should be able to authenticate with one tap via native Google CredentialManager without encountering browser redirects or "disallowed_useragent" errors.
- **SEC-PRD-03 (Immediate Account Ban Enforcement)**: If an administrator flags or bans an account in the admin panel, the mobile app session must terminate within 60 seconds (enforced via SSE disconnect and DAL rejection).
- **SEC-PRD-04 (Offline Data Security)**: Study logs cached locally during offline periods must be encrypted using hardware-backed keystore keys. Lost or stolen devices cannot be read via standard forensic extraction tools.
- **SEC-PRD-05 (Privacy & Telemetry)**: The application shall not collect advertising IDs (`AAID`) or hardware identifiers (IMEI/MAC). Device identifiers must be pseudorandom UUIDv4 strings.

### 8.2 Technical Requirements (TRD) — Engineering Contracts
- **SEC-TRD-01 (Target SDK & Alignment)**: Target SDK **API 36 (Android 16)** with minSdk **26 (Android 8.0)**. Ensure 16 KB page-size compatibility for all native dependencies.
- **SEC-TRD-02 (Transport Security)**: Enforce TLS 1.3 as standard. Disable all cleartext traffic. Reject invalid SSL certificates unconditionally.
- **SEC-TRD-03 (Web Message Bridge Validation)**: Origin checking must occur at the Chromium engine level using `WebViewCompat.addWebMessageListener`. All bridge messages must conform to strict JSON schemas.
- **SEC-TRD-04 (Defense-in-Depth DAL Checks)**: Every Server Action and API route must verify `getCurrentUser()` and validate resource ownership before mutating PostgreSQL records.
- **SEC-TRD-05 (Manifest Hardening)**: All internal activities, broadcast receivers, and services must be marked `android:exported="false"`. Non-exported receivers on Android 14+ must use `RECEIVER_NOT_EXPORTED`.

---

## 9. Step-by-Step Production Hardening Checklist

### Phase 1: Web Application Remediation (`v2`)
- [ ] Rename `v2/src/proxy.ts` to `v2/src/middleware.ts` or declare security headers directly in `next.config.ts`.
- [ ] Configure Content Security Policy (CSP) headers restricting script-src, object-src 'none', and frame-ancestors 'none'.
- [ ] In `v2/src/lib/auth.ts:getCurrentUser()`, replace the empty `catch` on `profiles.isBanned` with fail-closed behavior (throw or return null).
- [ ] In `v2/src/lib/validations/index.ts:CurriculumResourceSchema`, constrain `url` to `z.string().url().regex(/^https?:\/\/)` to eliminate `javascript:` XSS vectors.
- [ ] In `v2/src/actions/auth.ts:getGoogleOAuthUrlAction`, inject cryptographic `state` and set `oauth_state` cookie.
- [ ] Implement and test `/api/auth/native-google/route.ts` with signature verification via `https://www.googleapis.com/oauth2/v3/certs`.
- [ ] Add `v2/public/.well-known/assetlinks.json` with the production Play App Signing SHA-256 fingerprint.

### Phase 2: Android Project Architecture (`app`)
- [ ] Initialize Android project with `targetSdk = 36`, `compileSdk = 36`, and `minSdk = 26`.
- [ ] Add dependencies:
  - `androidx.webkit:webkit:1.12.0`
  - `androidx.credentials:credentials:1.3.0`
  - `androidx.credentials:credentials-play-services-auth:1.3.0`
  - `com.google.android.libraries.identity.googleid:googleid:1.1.1`
  - `androidx.datastore:datastore-preferences:1.1.1`
  - `com.squareup.okhttp3:okhttp:4.12.0`
- [ ] Create `res/xml/network_security_config.xml` disabling cleartext traffic.
- [ ] Declare `android:networkSecurityConfig="@xml/network_security_config"` and `android:allowBackup="false"` in `AndroidManifest.xml`.
- [ ] Implement `KeystoreCryptoManager.kt` using AndroidKeyStore AES-256-GCM.
- [ ] Implement `SecureWebViewFactory.kt` with disabled file/content schemes.
- [ ] Implement `SecureWebViewClient.kt` with SSL cancel and host allowlist.
- [ ] Implement `NativeMessageBridge.kt` using `WebViewCompat.addWebMessageListener`.
- [ ] Implement `SessionCookieSyncManager.kt` with `CookieManager.setCookie` and `flush()`.

### Phase 3: Verification & Security Auditing
- [ ] Execute `pnpm test:e2e` on `v2`.
- [ ] Test Google Sign-In with real Android emulator and physical device via `CredentialManager`.
- [ ] Verify SSL error cancellation by navigating WebView to an expired SSL test domain (e.g. `https://expired.badssl.com/`) and ensuring it blocks navigation immediately without proceeding.
- [ ] Inspect Android WebView via Chrome Remote Debugging in debug build to verify origins and bridge message exchange.
- [ ] Build release APK (`./gradlew assembleRelease`) and verify that `setWebContentsDebuggingEnabled` is disabled and debugging ports are closed.
- [ ] Run `adb shell pm get-app-links com.scholarpulse.app` to empirically verify domain verification status:
  ```bash
  adb shell pm get-app-links com.scholarpulse.app
  # Expected: scholarpulsev2.vercel.app: verified
  ```
