package com.mewmix.nabu.auth

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.mewmix.nabu.utils.DebugLogger

class CodexAuthenticator : OAuthManager {
    // Placeholder configuration.
    private val CLIENT_ID = "YOUR_CODEX_CLIENT_ID"
    private val REDIRECT_URI = "nabu://auth/callback/codex"
    private val AUTH_URL = "https://github.com/login/oauth/authorize" // Assuming GitHub Copilot flow
    private val SCOPES = "read:user"

    override fun initiateLogin(context: Context) {
        val authUri = Uri.parse(AUTH_URL).buildUpon()
            .appendQueryParameter("client_id", CLIENT_ID)
            .appendQueryParameter("redirect_uri", REDIRECT_URI)
            .appendQueryParameter("scope", SCOPES)
            .build()

        DebugLogger.log("CodexAuthenticator: Initiating login with URL: $authUri")
        val intent = Intent(Intent.ACTION_VIEW, authUri)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    override fun handleCallback(intent: Intent): Boolean {
        val data = intent.data ?: return false
        if (data.toString().startsWith(REDIRECT_URI)) {
            val code = data.getQueryParameter("code")
            if (code != null) {
                DebugLogger.log("CodexAuthenticator: Received auth code: $code")
                // TODO: Exchange code for token
                return true
            }
            val error = data.getQueryParameter("error")
            if (error != null) {
                DebugLogger.log("CodexAuthenticator: Auth error: $error")
                return true
            }
        }
        return false
    }

    override fun getAccessToken(context: Context): String? {
        // TODO: Retrieve stored token
        return null
    }

    override fun logout(context: Context) {
        // TODO: Clear stored token
        DebugLogger.log("CodexAuthenticator: Logout")
    }
}
