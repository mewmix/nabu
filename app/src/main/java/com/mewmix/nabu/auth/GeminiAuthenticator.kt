package com.mewmix.nabu.auth

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.mewmix.nabu.utils.DebugLogger
import com.mewmix.nabu.utils.SettingsManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class GeminiAuthenticator : OAuthManager {
    companion object {
        const val PROVIDER_ID = "google"
    }

    private val defaultRedirectUri = "nabu://auth/callback/google"
    private val authUrl = "https://accounts.google.com/o/oauth2/v2/auth"
    private val tokenUrl = "https://oauth2.googleapis.com/token"
    private val scopes = "https://www.googleapis.com/auth/cloud-platform"

    override fun initiateLogin(context: Context) {
        val appContext = context.applicationContext
        val clientId = SettingsManager.getGeminiOAuthClientId(appContext)
        if (clientId.isBlank()) {
            DebugLogger.log("GeminiAuthenticator: Missing OAuth client ID. Configure Gemini OAuth Client ID in Settings.")
            return
        }
        val redirectUri = SettingsManager.getGeminiOAuthRedirectUri(appContext, defaultRedirectUri)
        val session = OAuthSessionStore.createSession(appContext, PROVIDER_ID, redirectUri)

        val authUri = Uri.parse(authUrl).buildUpon()
            .appendQueryParameter("client_id", clientId)
            .appendQueryParameter("redirect_uri", redirectUri)
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("scope", scopes)
            .appendQueryParameter("access_type", "offline")
            .appendQueryParameter("prompt", "consent")
            .appendQueryParameter("state", session.state)
            .appendQueryParameter("code_challenge", OAuthSessionStore.buildCodeChallenge(session.codeVerifier))
            .appendQueryParameter("code_challenge_method", "S256")
            .build()

        DebugLogger.log("GeminiAuthenticator: Initiating login with URL: $authUri")
        val intent = Intent(Intent.ACTION_VIEW, authUri).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    override suspend fun handleCallback(context: Context, intent: Intent): Boolean {
        val data = intent.data ?: return false
        if (!isExpectedCallback(data)) return false

        val appContext = context.applicationContext
        val clientId = SettingsManager.getGeminiOAuthClientId(appContext)
        if (clientId.isBlank()) {
            DebugLogger.log("GeminiAuthenticator: Callback received but Gemini OAuth client ID is missing")
            return false
        }
        val error = data.getQueryParameter("error")
        if (!error.isNullOrBlank()) {
            OAuthSessionStore.clearSession(appContext, PROVIDER_ID)
            DebugLogger.log("GeminiAuthenticator: Auth error: $error")
            return true
        }

        val session = OAuthSessionStore.consumeIfValid(
            context = appContext,
            providerId = PROVIDER_ID,
            state = data.getQueryParameter("state")
        ) ?: run {
            DebugLogger.log("GeminiAuthenticator: Callback rejected due to invalid/missing OAuth state")
            return false
        }

        val code = data.getQueryParameter("code")
        if (code.isNullOrBlank()) {
            DebugLogger.log("GeminiAuthenticator: Callback missing code")
            return false
        }
        val redirectUri = session.redirectUri ?: SettingsManager.getGeminiOAuthRedirectUri(
            appContext,
            defaultRedirectUri
        )

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
                    DebugLogger.log("GeminiAuthenticator: Token exchange returned no access token")
                    false
                } else {
                    OAuthTokenStore.save(
                        context = appContext,
                        providerId = PROVIDER_ID,
                        accessToken = accessToken,
                        refreshToken = json.optString("refresh_token").ifBlank { null },
                        idToken = json.optString("id_token").ifBlank { null },
                        expiresInSeconds = json.optLong("expires_in").takeIf { it > 0L },
                        accountId = null
                    )
                    DebugLogger.log("GeminiAuthenticator: OAuth token exchange complete")
                    true
                }
            },
            onFailure = { errorThrowable ->
                DebugLogger.log("GeminiAuthenticator: Token exchange failed: ${errorThrowable.message}")
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

    suspend fun getValidAccessToken(context: Context): String? {
        val appContext = context.applicationContext
        val clientId = SettingsManager.getGeminiOAuthClientId(appContext)
        if (clientId.isBlank()) return null
        val tokens = OAuthTokenStore.load(appContext, PROVIDER_ID) ?: return null
        if (!tokens.isExpired()) return tokens.accessToken
        val refreshToken = tokens.refreshToken ?: return null

        val refreshed = withContext(Dispatchers.IO) {
            OAuthHttpClient.postForm(
                url = tokenUrl,
                fields = mapOf(
                    "grant_type" to "refresh_token",
                    "client_id" to clientId,
                    "refresh_token" to refreshToken
                )
            )
        }
        return refreshed.fold(
            onSuccess = { json ->
                val accessToken = json.optString("access_token").ifBlank { null } ?: return@fold null
                OAuthTokenStore.save(
                    context = appContext,
                    providerId = PROVIDER_ID,
                    accessToken = accessToken,
                    refreshToken = json.optString("refresh_token").ifBlank { null } ?: refreshToken,
                    idToken = json.optString("id_token").ifBlank { null } ?: tokens.idToken,
                    expiresInSeconds = json.optLong("expires_in").takeIf { it > 0L },
                    accountId = tokens.accountId
                )
                accessToken
            },
            onFailure = { null }
        )
    }

    override fun logout(context: Context) {
        val appContext = context.applicationContext
        OAuthSessionStore.clearSession(appContext, PROVIDER_ID)
        OAuthTokenStore.clear(appContext, PROVIDER_ID)
        DebugLogger.log("GeminiAuthenticator: Logout")
    }

    private fun isExpectedCallback(uri: Uri): Boolean =
        uri.scheme == "nabu" &&
            uri.host == "auth" &&
            (uri.path == "/callback/google" || uri.path == "/auth/callback")
}
