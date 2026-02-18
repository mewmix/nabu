package com.mewmix.nabu.tools

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GlaiveBridgeInstrumentedTest {

    @Test
    fun providerDiscoveryAndToolExecutionWork() {
        runBlocking {
            val context = ApplicationProvider.getApplicationContext<Context>()
            assertTrue("Glaive must be installed for this test", GlaiveBridge.isInstalled(context))

            val permissionState = context.packageManager.checkPermission(
                "com.mewmix.glaive.permission.ACCESS_TOOLS",
                context.packageName
            )
            assertTrue(
                "Nabu does not hold com.mewmix.glaive.permission.ACCESS_TOOLS",
                permissionState == PackageManager.PERMISSION_GRANTED
            )

            val uri = Uri.parse("content://com.mewmix.glaive.tool_provider")
            val discoveredTools = mutableListOf<String>()
            context.contentResolver.query(uri, null, null, null, null).use { cursor ->
                assertNotNull("Tool provider query returned null cursor", cursor)
                if (cursor != null) {
                    val nameIndex = cursor.getColumnIndexOrThrow("name")
                    while (cursor.moveToNext()) {
                        discoveredTools += cursor.getString(nameIndex)
                    }
                }
            }

            assertTrue("Expected list_files in discovered tools", discoveredTools.contains("list_files"))

            val result = withTimeout(15_000) {
                GlaiveBridge.executeTool(
                    context = context,
                    call = ToolCall(
                        toolName = "list_files",
                        arguments = mapOf("path" to "/")
                    )
                )
            }

            assertFalse("Tool call failed: ${result.output}", result.isError)
            JSONArray(result.output)
        }
    }
}
