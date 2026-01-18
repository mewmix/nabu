package com.mewmix.nabu.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mewmix.nabu.utils.DebugLogger // This import will now resolve correctly
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.lang.StringBuilder

data class ChatMessage(val message: String, val isFromUser: Boolean)

class ChatViewModel(private val llmInference: LlmInference) : ViewModel() {

    private val _chatState = MutableStateFlow<List<ChatMessage>>(emptyList())
    val chatState: StateFlow<List<ChatMessage>> = _chatState.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val conversationHistory = mutableListOf<LlmMessage>()

    fun sendMessage(message: String) {
        val trimmed = message.trim()
        if (trimmed.isEmpty()) return
        DebugLogger.log("ChatViewModel sendMessage: $trimmed")
        _chatState.value = _chatState.value + ChatMessage(trimmed, true)
        conversationHistory.add(LlmMessage(role = "user", content = trimmed))
        _isLoading.value = true

        viewModelScope.launch(Dispatchers.IO) {
            val responseBuilder = StringBuilder()
            llmInference.sendMessage(conversationHistory.toList()) { partialResult, done ->
                if (done) {
                    _isLoading.value = false
                    DebugLogger.log("ChatViewModel response complete")
                    val finalResponse = responseBuilder.toString()
                    if (finalResponse.isNotBlank()) {
                        conversationHistory.add(LlmMessage(role = "agent", content = finalResponse))
                    }
                } else {
                    responseBuilder.append(partialResult)
                }
                val currentAssistantMessage = responseBuilder.toString()
                val lastMessage = _chatState.value.lastOrNull { !it.isFromUser }
                if (lastMessage != null) {
                    val updatedMessage = lastMessage.copy(message = currentAssistantMessage)
                    _chatState.value = _chatState.value.dropLast(1) + updatedMessage
                } else if (currentAssistantMessage.isNotEmpty()) {
                    _chatState.value = _chatState.value + ChatMessage(currentAssistantMessage, false)
                }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        llmInference.close()
    }
}
