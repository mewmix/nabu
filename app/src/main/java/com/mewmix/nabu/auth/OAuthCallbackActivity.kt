package com.mewmix.nabu.auth

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.mewmix.nabu.utils.DebugLogger
import com.mewmix.nabu.MainActivity

class OAuthCallbackActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val intent = intent
        val data = intent.data
        DebugLogger.log("OAuthCallbackActivity: Received intent: $data")

        var handled = false
        // Determine which authenticator to use based on URI
        if (data != null) {
            val uriString = data.toString()
            if (uriString.contains("callback/google")) {
                handled = GeminiAuthenticator().handleCallback(intent)
            } else if (uriString.contains("callback/codex")) {
                handled = CodexAuthenticator().handleCallback(intent)
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

        // Return to main app after a short delay or immediately
        // For now, let's just finish and bring main activity to front
        if (handled) {
            val mainIntent = Intent(this, MainActivity::class.java)
            mainIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            startActivity(mainIntent)
            finish()
        }
    }
}
