package com.mewmix.nabu.auth

import android.content.Context
import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom

internal object OAuthSessionStore {
    private const val PREFS_NAME = "oauth_session_store"
    private const val SESSION_TTL_MS = 10 * 60 * 1000L
    private val secureRandom = SecureRandom()

    data class Session(
        val state: String,
        val codeVerifier: String,
        val redirectUri: String?
    )

    fun createSession(context: Context, providerId: String, redirectUri: String? = null): Session {
        val session = Session(
            state = randomUrlSafe(24),
            codeVerifier = randomUrlSafe(64),
            redirectUri = redirectUri?.ifBlank { null }
        )
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putString(stateKey(providerId), session.state)
            .putString(verifierKey(providerId), session.codeVerifier)
            .putString(redirectKey(providerId), session.redirectUri)
            .putLong(createdAtKey(providerId), System.currentTimeMillis())
            .apply()
        return session
    }

    fun consumeIfValid(context: Context, providerId: String, state: String?): Session? {
        if (state.isNullOrBlank()) return null

        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val storedState = prefs.getString(stateKey(providerId), null)
        val storedVerifier = prefs.getString(verifierKey(providerId), null)
        val storedRedirect = prefs.getString(redirectKey(providerId), null)
        val createdAt = prefs.getLong(createdAtKey(providerId), 0L)

        clearSession(context, providerId)

        if (storedState.isNullOrBlank() || storedVerifier.isNullOrBlank()) return null
        if (storedState != state) return null
        if (createdAt <= 0L || (System.currentTimeMillis() - createdAt) > SESSION_TTL_MS) return null

        return Session(
            state = storedState,
            codeVerifier = storedVerifier,
            redirectUri = storedRedirect?.ifBlank { null }
        )
    }

    fun clearSession(context: Context, providerId: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .remove(stateKey(providerId))
            .remove(verifierKey(providerId))
            .remove(redirectKey(providerId))
            .remove(createdAtKey(providerId))
            .apply()
    }

    fun providerForState(context: Context, state: String?): String? {
        if (state.isNullOrBlank()) return null
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val entries = prefs.all
        return entries.entries.firstNotNullOfOrNull { (key, value) ->
            if (!key.startsWith("state_")) return@firstNotNullOfOrNull null
            val valueString = value as? String ?: return@firstNotNullOfOrNull null
            if (valueString == state) key.removePrefix("state_") else null
        }
    }

    fun buildCodeChallenge(codeVerifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(codeVerifier.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(digest, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    private fun randomUrlSafe(bytes: Int): String {
        val buffer = ByteArray(bytes)
        secureRandom.nextBytes(buffer)
        return Base64.encodeToString(buffer, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    private fun stateKey(providerId: String): String = "state_$providerId"
    private fun verifierKey(providerId: String): String = "verifier_$providerId"
    private fun redirectKey(providerId: String): String = "redirect_$providerId"
    private fun createdAtKey(providerId: String): String = "created_$providerId"
}
