package com.mewmix.nabu.auth

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import com.mewmix.nabu.utils.DebugLogger
import com.mewmix.nabu.MainActivity
import kotlinx.coroutines.launch

class OAuthCallbackActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val intent = intent
        val data = intent.data
        DebugLogger.log("OAuthCallbackActivity: Received intent: $data")

        lifecycleScope.launch {
            var handled = false
            if (data != null) {
                when (resolveProvider(data)) {
                    "google" -> handled = GeminiAuthenticator().handleCallback(this@OAuthCallbackActivity, intent)
                    "codex" -> handled = CodexAuthenticator().handleCallback(this@OAuthCallbackActivity, intent)
                    else -> DebugLogger.log("OAuthCallbackActivity: Ignoring unsupported callback URI: $data")
                }
            }

            setContent {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    if (handled) {
                        Text("Authentication successful! You can close this window.")
                    } else {
                        Text("Authentication failed or cancelled.")
                    }
                }
            }

            if (handled) {
                val mainIntent = Intent(this@OAuthCallbackActivity, MainActivity::class.java)
                mainIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                startActivity(mainIntent)
                finish()
            }
        }
    }

    private fun resolveProvider(uri: Uri): String? {
        if (uri.scheme != "nabu" || uri.host != "auth") return null
        uri.getQueryParameter("provider")?.let { provider ->
            if (provider == "google" || provider == "codex") return provider
        }
        val segments = uri.pathSegments
        if (segments.isEmpty()) return null
        if (segments[0] == "callback" && segments.size >= 2) {
            return segments[1]
        }
        if (segments.size >= 2 && segments[0] == "auth" && segments[1] == "callback") {
            val state = uri.getQueryParameter("state")
            return OAuthSessionStore.providerForState(this, state)
        }
        return null
    }
}
