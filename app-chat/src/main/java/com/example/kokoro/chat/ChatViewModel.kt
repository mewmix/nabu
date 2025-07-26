package com.example.kokoro.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class ChatMessage(val message: String, val isFromUser: Boolean)

class ChatViewModel(private val llmInference: LlmInference) : ViewModel() {

    private val _chatState = MutableStateFlow<List<ChatMessage>>(emptyList())
    val chatState: StateFlow<List<ChatMessage>> = _chatState.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    fun sendMessage(message: String) {
        _chatState.value = _chatState.value + ChatMessage(message, true)
        _isLoading.value = true

        viewModelScope.launch(Dispatchers.IO) {
            llmInference.sendMessage(message) { partialResult, done ->
                if (done) {
                    _isLoading.value = false
                } else {
                    val lastMessage = _chatState.value.lastOrNull { !it.isFromUser }
                    if (lastMessage != null) {
                        val updatedMessage = lastMessage.copy(message = lastMessage.message + partialResult)
                        _chatState.value = _chatState.value.dropLast(1) + updatedMessage
                    } else {
                        _chatState.value = _chatState.value + ChatMessage(partialResult, false)
                    }
                }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        llmInference.close()
    }
}
