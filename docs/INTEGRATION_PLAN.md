# Nabu Integration Plan: Glaive Bridge & AI OAuth

This document outlines the investigation and architecture for integrating the "Glaive" file manager bridge and OAuth flows for Gemini and Codex CLI into Nabu.

## 1. Glaive Bridge Integration

### Overview
Glaive is an external file manager application. The goal is to allow the local AI in Nabu to call tools provided by Glaive (e.g., file operations) locally.

### Architecture

#### Tool Definition
We define a common `Tool` schema within Nabu to represent available capabilities.

```kotlin
data class Tool(
    val name: String,
    val description: String,
    val parameters: Map<String, String> // Simplified schema for now
)
```

#### Communication Protocol
Since Glaive is an external Android app, we will use Android Intents for inter-process communication (IPC).

- **Discovery:** Nabu can query for a specific ContentProvider or broadcast receiver exposed by Glaive to get a list of supported tools.
- **Execution:** Nabu sends an `Intent` with an action (e.g., `com.mewmix.glaive.ACTION_EXECUTE_TOOL`) and extras containing the tool name and parameters (JSON).
- **Result:** Glaive returns the result via `onActivityResult` or a broadcast.

#### Proposed Implementation
1.  **`ToolRegistry`:** A singleton in Nabu to manage available tools.
2.  **`GlaiveBridge`:** A helper class to handle Intent construction and result parsing.
3.  **`ChatViewModel` Integration:** The LLM's system prompt will be updated to include tool descriptions. The output parser will detect tool calls (e.g., specific JSON structure) and delegate execution to `GlaiveBridge`.

### Investigation Findings
- **Protocol:** Assumed to be Intent-based given the "local call" nature on Android.
- **Security:** Permissions might be required to interact with Glaive. Nabu should declare `<queries>` in Manifest if targeting Android 11+.

## 2. OAuth Integrations (Gemini & Codex CLI)

### Overview
Users want to use Nabu as a harness for authenticating with Gemini (Google) and Codex CLI (OpenAI/GitHub) to potentially use their APIs or CLI features.

### Architecture

#### generic `OAuthManager`
A unified interface for handling OAuth flows.

```kotlin
interface OAuthManager {
    fun initiateLogin(activity: Activity)
    fun handleCallback(intent: Intent): Boolean
    fun getAccessToken(): String?
}
```

#### Gemini Integration
- **Provider:** Google (Vertex AI or AI Studio).
- **Flow:** Standard OAuth 2.0 or `GoogleSignInClient` (if using Play Services).
- **Scopes:** `https://www.googleapis.com/auth/cloud-platform` (for Vertex AI).
- **Implementation:**
    - Use `CustomTabsIntent` to open the auth URL.
    - Handle redirect to `nabu://auth/callback/google`.
    - Exchange code for token.

#### Codex CLI Integration
- **Provider:** OpenAI or GitHub (if referring to Copilot).
- **Flow:** OpenAI typically uses API keys, but "Codex CLI" suggests a specific tool that might use a device flow or web-based login.
- **Assumption:** We will implement a standard OAuth 2.0 Authorization Code flow for OpenAI/GitHub.
- **Redirect URI:** `nabu://auth/callback/codex`.

### Implementation Plan
1.  **`OAuthCallbackActivity`:** An Activity registered in Manifest to intercept `nabu://auth/callback` redirects.
2.  **`SettingsScreen`:** UI buttons to trigger login for each provider.
3.  **Persistence:** Store tokens securely (e.g., `EncryptedSharedPreferences` - for now standard Prefs/Memory for investigation).

## 3. Next Steps
1.  Implement the scaffolding defined in this plan.
2.  Verify Intent handling with a mock "Glaive" if the real app is not installed.
3.  Test OAuth flows with real client IDs (user must provide them).
