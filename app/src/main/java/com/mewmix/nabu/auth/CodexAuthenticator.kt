package com.mewmix.nabu.auth

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.mewmix.nabu.utils.DebugLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class CodexAuthenticator : OAuthManager {
    companion object {
        const val PROVIDER_ID = "codex"
        private const val LOOPBACK_TIMEOUT_MS = 5 * 60 * 1000L
        private const val CODEX_LOOPBACK_PORT = 1455
    }

    // Matches Codex CLI OAuth defaults from openai/codex.
    private val clientId = "app_EMoamEEZ73f0CkXaXp7hrann"
    private val defaultRedirectUri = "nabu://auth/callback/codex"
    private val authUrl = "https://auth.openai.com/oauth/authorize"
    private val tokenUrl = "https://auth.openai.com/oauth/token"
    private val scopes = "openid profile email offline_access"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var activeLoopback: OAuthLoopbackReceiver.Session? = null

    override fun initiateLogin(context: Context) {
        val appContext = context.applicationContext
        activeLoopback?.close()
        val loopback = runCatching {
            OAuthLoopbackReceiver.start("/auth/callback", preferredPort = CODEX_LOOPBACK_PORT)
        }.getOrNull()
        activeLoopback = loopback
        val redirectUri = loopback?.redirectUri ?: defaultRedirectUri
        val session = OAuthSessionStore.createSession(appContext, PROVIDER_ID, redirectUri)

        val authUri = Uri.parse(authUrl).buildUpon()
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("client_id", clientId)
            .appendQueryParameter("redirect_uri", redirectUri)
            .appendQueryParameter("scope", scopes)
            .appendQueryParameter("state", session.state)
            .appendQueryParameter("code_challenge", OAuthSessionStore.buildCodeChallenge(session.codeVerifier))
            .appendQueryParameter("code_challenge_method", "S256")
            .appendQueryParameter("id_token_add_organizations", "true")
            .appendQueryParameter("codex_cli_simplified_flow", "true")
            .appendQueryParameter("originator", "nabu")
            .build()

        DebugLogger.log("CodexAuthenticator: Initiating login with URL: $authUri")
        val intent = Intent(Intent.ACTION_VIEW, authUri).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)

        if (loopback != null) {
            scope.launch {
                val callbackUri = loopback.awaitCallback(LOOPBACK_TIMEOUT_MS)
                if (callbackUri != null) {
                    val handled = handleCallbackUri(appContext, callbackUri)
                    DebugLogger.log("CodexAuthenticator: Loopback callback handled=$handled")
                } else {
                    OAuthSessionStore.clearSession(appContext, PROVIDER_ID)
                    DebugLogger.log("CodexAuthenticator: Loopback callback timed out")
                }
                if (activeLoopback === loopback) {
                    activeLoopback = null
                }
                loopback.close()
            }
        } else {
            DebugLogger.log(
                "CodexAuthenticator: Loopback bind on :$CODEX_LOOPBACK_PORT unavailable, " +
                    "falling back to custom-scheme callback."
            )
        }
    }

    override suspend fun handleCallback(context: Context, intent: Intent): Boolean {
        val data = intent.data ?: return false
        return handleCallbackUri(context.applicationContext, data)
    }

    private suspend fun handleCallbackUri(appContext: Context, data: Uri): Boolean {
        if (!isExpectedCallback(data)) return false

        val error = data.getQueryParameter("error")
        if (!error.isNullOrBlank()) {
            OAuthSessionStore.clearSession(appContext, PROVIDER_ID)
            DebugLogger.log("CodexAuthenticator: Auth error: $error")
            return true
        }

        val session = OAuthSessionStore.consumeIfValid(
            context = appContext,
            providerId = PROVIDER_ID,
            state = data.getQueryParameter("state")
        ) ?: run {
            DebugLogger.log("CodexAuthenticator: Callback rejected due to invalid/missing OAuth state")
            return false
        }

        val code = data.getQueryParameter("code")
        if (code.isNullOrBlank()) {
            DebugLogger.log("CodexAuthenticator: Callback missing code")
            return false
        }
        val redirectUri = session.redirectUri ?: defaultRedirectUri

        val tokenResponse = withContext(Dispatchers.IO) {
            OAuthHttpClient.postForm(
                url = tokenUrl,
                fields = mapOf(
                    "grant_type" to "authorization_code",
                    "client_id" to clientId,
                    "code" to code,
                    "redirect_uri" to redirectUri,
                    "code_verifier" to session.codeVerifier
                )
            )
        }

        return tokenResponse.fold(
            onSuccess = { json ->
                val accessToken = json.optString("access_token").ifBlank { null }
                if (accessToken.isNullOrBlank()) {
                    DebugLogger.log("CodexAuthenticator: Token exchange returned no access token")
                    false
                } else {
                    val idToken = json.optString("id_token").ifBlank { null }
                    val accountId = JwtUtils.codexAccountId(accessToken, idToken)
                    OAuthTokenStore.save(
                        context = appContext,
                        providerId = PROVIDER_ID,
                        accessToken = accessToken,
                        refreshToken = json.optString("refresh_token").ifBlank { null },
                        idToken = idToken,
                        expiresInSeconds = json.optLong("expires_in").takeIf { it > 0L },
                        accountId = accountId
                    )
                    DebugLogger.log("CodexAuthenticator: OAuth token exchange complete")
                    true
                }
            },
            onFailure = { errorThrowable ->
                DebugLogger.log("CodexAuthenticator: Token exchange failed: ${errorThrowable.message}")
                false
            }
        )
    }

    override fun getAccessToken(context: Context): String? {
        val tokens = OAuthTokenStore.load(context.applicationContext, PROVIDER_ID) ?: return null
        return if (!tokens.isExpired()) tokens.accessToken else null
    }

    fun hasStoredSession(context: Context): Boolean =
        OAuthTokenStore.load(context.applicationContext, PROVIDER_ID) != null

    fun getAccountId(context: Context): String? =
        OAuthTokenStore.load(context.applicationContext, PROVIDER_ID)?.accountId

    suspend fun getValidAccessToken(context: Context): String? {
        val appContext = context.applicationContext
        val tokens = OAuthTokenStore.load(appContext, PROVIDER_ID) ?: return null
        if (!tokens.isExpired()) return tokens.accessToken
        val refreshToken = tokens.refreshToken ?: return null

        val refreshed = withContext(Dispatchers.IO) {
            OAuthHttpClient.postForm(
                url = tokenUrl,
                fields = mapOf(
                    "client_id" to clientId,
                    "grant_type" to "refresh_token",
                    "refresh_token" to refreshToken,
                    "scope" to "openid profile email offline_access"
                )
            )
        }
        return refreshed.fold(
            onSuccess = { json ->
                val accessToken = json.optString("access_token").ifBlank { null } ?: return@fold null
                val newRefreshToken = json.optString("refresh_token").ifBlank { null } ?: refreshToken
                val newIdToken = json.optString("id_token").ifBlank { null } ?: tokens.idToken
                val accountId = JwtUtils.codexAccountId(accessToken, newIdToken) ?: tokens.accountId
                OAuthTokenStore.save(
                    context = appContext,
                    providerId = PROVIDER_ID,
                    accessToken = accessToken,
                    refreshToken = newRefreshToken,
                    idToken = newIdToken,
                    expiresInSeconds = json.optLong("expires_in").takeIf { it > 0L },
                    accountId = accountId
                )
                accessToken
            },
            onFailure = { null }
        )
    }

    override fun logout(context: Context) {
        val appContext = context.applicationContext
        activeLoopback?.close()
        activeLoopback = null
        OAuthSessionStore.clearSession(appContext, PROVIDER_ID)
        OAuthTokenStore.clear(appContext, PROVIDER_ID)
        DebugLogger.log("CodexAuthenticator: Logout")
    }

    private fun isExpectedCallback(uri: Uri): Boolean =
        (uri.scheme == "nabu" &&
            uri.host == "auth" &&
            (uri.path == "/callback/codex" || uri.path == "/auth/callback")) ||
            ((uri.scheme == "http" || uri.scheme == "https") &&
                (uri.host == "127.0.0.1" || uri.host == "localhost") &&
                uri.path == "/auth/callback")
}
