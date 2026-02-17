package com.mewmix.nabu.tools

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

object ToolRegistry {
    private val _tools = MutableStateFlow<List<Tool>>(emptyList())
    val tools = _tools.asStateFlow()

    fun register(tool: Tool) {
        val current = _tools.value.toMutableList()
        current.removeAll { it.name == tool.name }
        current.add(tool)
        _tools.value = current
    }

    fun unregister(toolName: String) {
        val current = _tools.value.toMutableList()
        current.removeAll { it.name == toolName }
        _tools.value = current
    }

    fun getTool(name: String): Tool? {
        return _tools.value.find { it.name == name }
    }
}
