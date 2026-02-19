package com.mewmix.nabu.tools

import org.junit.Assert.assertTrue
import org.junit.Test

class GlaiveBridgeToolRegistrationTest {

    @Test
    fun registerDefaultTools_includesExtendedFileTools() {
        val expected = listOf("create_dir", "delete_file", "search_files")
        expected.forEach { ToolRegistry.unregister(it) }

        GlaiveBridge.registerDefaultTools()

        val names = ToolRegistry.tools.value.map { it.name }.toSet()
        expected.forEach { name ->
            assertTrue("Expected tool registration for $name", name in names)
        }
    }
}
