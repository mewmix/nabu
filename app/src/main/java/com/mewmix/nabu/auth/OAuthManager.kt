package com.mewmix.nabu.auth

import android.content.Context
import android.content.Intent

/**
 * Interface for managing OAuth authentication flows.
 */
interface OAuthManager {
    /**
     * Initiates the login flow.
     */
    fun initiateLogin(context: Context)

    /**
     * Handles the callback intent from the OAuth provider (e.g. redirect URI).
     * @return true if the intent was handled, false otherwise.
     */
    fun handleCallback(intent: Intent): Boolean

    /**
     * Returns the current access token if logged in.
     */
    fun getAccessToken(context: Context): String?

    /**
     * Logs out and clears stored tokens.
     */
    fun logout(context: Context)

    /**
     * Checks if the user is currently authenticated.
     */
    fun isAuthenticated(context: Context): Boolean {
        return getAccessToken(context) != null
    }
}
