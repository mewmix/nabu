package com.mewmix.nabu.api

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mewmix.nabu.utils.SettingsManager
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.Socket

@RunWith(AndroidJUnit4::class)
class ApiServerInstrumentedTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        ApiServerManager.stop()
        SettingsManager.setApiEnabled(context, true)
        SettingsManager.setApiLanEnabled(context, true)
        ApiServerManager.syncWithSettings(context)
    }

    @After
    fun tearDown() {
        ApiServerManager.stop()
        SettingsManager.setApiEnabled(context, false)
        SettingsManager.setApiLanEnabled(context, false)
        removeDummyTaskModel(NON_VISION_MODEL_ID)
    }

    @Test
    fun healthAndModelsEndpointsRespond() {
        val health = rawHttpGet("/health")
        assertEquals(200, health.first)
        val healthJson = JSONObject(health.second)
        assertTrue(healthJson.optBoolean("ok"))

        val legacyModels = rawHttpGet("/models")
        assertEquals(200, legacyModels.first)
        val legacyModelsJson = JSONObject(legacyModels.second)
        assertTrue(legacyModelsJson.has("models"))
        val legacyData = legacyModelsJson.optJSONArray("models")
        if (legacyData != null && legacyData.length() > 0) {
            assertTrue(legacyData.getJSONObject(0).has("capabilities"))
        }

        val models = rawHttpGet("/v1/models")
        assertEquals(200, models.first)
        val modelsJson = JSONObject(models.second)
        assertEquals("list", modelsJson.optString("object"))
        assertTrue(modelsJson.has("data"))
        val v1Data = modelsJson.optJSONArray("data")
        if (v1Data != null && v1Data.length() > 0) {
            val metadata = v1Data.getJSONObject(0).optJSONObject("metadata")
            assertTrue(metadata?.has("capabilities") == true)
        }

        val ttsModels = rawHttpGet("/models?type=tts")
        assertEquals(200, ttsModels.first)
        val ttsModelsJson = JSONObject(ttsModels.second)
        assertTrue(ttsModelsJson.has("models"))

        val ttsV1Models = rawHttpGet("/v1/tts/models")
        assertEquals(200, ttsV1Models.first)
        val ttsV1ModelsJson = JSONObject(ttsV1Models.second)
        assertEquals("list", ttsV1ModelsJson.optString("object"))
        assertTrue(ttsV1ModelsJson.has("data"))

        val invalidType = rawHttpGet("/models?type=not-real")
        assertEquals(400, invalidType.first)
        val invalidTypeJson = JSONObject(invalidType.second)
        assertTrue(invalidTypeJson.has("error"))

        val postTtsMissingInput = rawHttpPost("/tts/speech", "{}")
        assertEquals(400, postTtsMissingInput.first)
        val postTtsMissingInputJson = JSONObject(postTtsMissingInput.second)
        assertTrue(postTtsMissingInputJson.has("error"))

        val postOpenAiTtsMissingInput = rawHttpPost("/v1/audio/speech", "{}")
        assertEquals(400, postOpenAiTtsMissingInput.first)
        val postOpenAiTtsMissingInputJson = JSONObject(postOpenAiTtsMissingInput.second)
        assertTrue(postOpenAiTtsMissingInputJson.has("error"))
    }

    @Test
    fun generateRejectsInvalidImageAndReplyTtsPayloads() {
        val invalidImage = rawHttpPost(
            "/generate",
            """
                {
                  "prompt":"describe the image",
                  "image_base64":"not-a-valid-base64"
                }
            """.trimIndent()
        )
        assertEquals(400, invalidImage.first)
        assertTrue(JSONObject(invalidImage.second).has("error"))

        val invalidReplyTts = rawHttpPost(
            "/generate",
            """
                {
                  "prompt":"hello",
                  "tts":{
                    "enabled":true,
                    "response_format":"wav"
                  }
                }
            """.trimIndent()
        )
        assertEquals(400, invalidReplyTts.first)
        assertTrue(JSONObject(invalidReplyTts.second).has("error"))

        val streamWithReplyTts = rawHttpPost(
            "/generate",
            """
                {
                  "prompt":"hello",
                  "stream":true,
                  "tts":true
                }
            """.trimIndent()
        )
        assertEquals(400, streamWithReplyTts.first)
        assertTrue(JSONObject(streamWithReplyTts.second).has("error"))

        val unsupportedRemoteImage = rawHttpPost(
            "/v1/chat/completions",
            """
                {
                  "messages":[
                    {
                      "role":"user",
                      "content":[
                        {"type":"text","text":"what is this"},
                        {"type":"image_url","image_url":{"url":"https://example.com/image.png"}}
                      ]
                    }
                  ]
                }
            """.trimIndent()
        )
        assertEquals(400, unsupportedRemoteImage.first)
        assertTrue(JSONObject(unsupportedRemoteImage.second).has("error"))

        val streamChatWithReplyTts = rawHttpPost(
            "/v1/chat/completions",
            """
                {
                  "stream":true,
                  "tts":{"enabled":true},
                  "messages":[
                    {"role":"user","content":"hello"}
                  ]
                }
            """.trimIndent()
        )
        assertEquals(400, streamChatWithReplyTts.first)
        assertTrue(JSONObject(streamChatWithReplyTts.second).has("error"))
    }

    @Test
    fun generateImageRequestsAreGatedToVisionModels() {
        installDummyTaskModel(NON_VISION_MODEL_ID)

        val explicitUnsupportedModel = rawHttpPost(
            "/generate",
            """
                {
                  "model":"$NON_VISION_MODEL_ID",
                  "prompt":"what is in this image?",
                  "image_base64":"$ONE_PIXEL_IMAGE_BASE64"
                }
            """.trimIndent()
        )
        assertEquals(400, explicitUnsupportedModel.first)
        val explicitErrorMessage = JSONObject(explicitUnsupportedModel.second)
            .optJSONObject("error")
            ?.optString("message")
            .orEmpty()
        assertTrue(explicitErrorMessage.contains("does not support image input"))

        val imageOnlyWithoutPrompt = rawHttpPost(
            "/generate",
            """
                {
                  "image_base64":"$ONE_PIXEL_IMAGE_BASE64"
                }
            """.trimIndent()
        )
        assertEquals(400, imageOnlyWithoutPrompt.first)
        val imageOnlyErrorMessage = JSONObject(imageOnlyWithoutPrompt.second)
            .optJSONObject("error")
            ?.optString("message")
            .orEmpty()
        assertTrue(imageOnlyErrorMessage.contains("image-capable"))
    }

    private fun installDummyTaskModel(modelId: String) {
        val modelDir = File(context.filesDir, "models")
        if (!modelDir.exists()) {
            modelDir.mkdirs()
        }
        File(modelDir, "$modelId.task").writeBytes(byteArrayOf(0x01.toByte()))
    }

    private fun removeDummyTaskModel(modelId: String) {
        File(context.filesDir, "models/$modelId.task").delete()
    }

    private fun rawHttpGet(path: String): Pair<Int, String> {
        Socket("127.0.0.1", ApiServerManager.PORT).use { socket ->
            socket.soTimeout = 5_000
            val request = buildString {
                append("GET $path HTTP/1.1\r\n")
                append("Host: 127.0.0.1\r\n")
                append("Connection: close\r\n\r\n")
            }
            socket.getOutputStream().write(request.toByteArray(Charsets.UTF_8))
            socket.getOutputStream().flush()

            val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
            val statusLine = reader.readLine() ?: error("Missing HTTP status line")
            val statusCode = statusLine.split(" ").getOrNull(1)?.toIntOrNull()
                ?: error("Unable to parse HTTP status from: $statusLine")

            var line: String?
            while (true) {
                line = reader.readLine() ?: break
                if (line.isEmpty()) break
            }

            val body = buildString {
                while (true) {
                    val bodyLine = reader.readLine() ?: break
                    append(bodyLine)
                }
            }
            return statusCode to body
        }
    }

    private fun rawHttpPost(path: String, jsonBody: String): Pair<Int, String> {
        Socket("127.0.0.1", ApiServerManager.PORT).use { socket ->
            socket.soTimeout = 5_000
            val bodyBytes = jsonBody.toByteArray(Charsets.UTF_8)
            val request = buildString {
                append("POST $path HTTP/1.1\r\n")
                append("Host: 127.0.0.1\r\n")
                append("Content-Type: application/json\r\n")
                append("Content-Length: ${bodyBytes.size}\r\n")
                append("Connection: close\r\n\r\n")
            }.toByteArray(Charsets.UTF_8)

            socket.getOutputStream().write(request)
            socket.getOutputStream().write(bodyBytes)
            socket.getOutputStream().flush()

            val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
            val statusLine = reader.readLine() ?: error("Missing HTTP status line")
            val statusCode = statusLine.split(" ").getOrNull(1)?.toIntOrNull()
                ?: error("Unable to parse HTTP status from: $statusLine")

            var line: String?
            while (true) {
                line = reader.readLine() ?: break
                if (line.isEmpty()) break
            }

            val body = buildString {
                while (true) {
                    val bodyLine = reader.readLine() ?: break
                    append(bodyLine)
                }
            }
            return statusCode to body
        }
    }

    companion object {
        private const val NON_VISION_MODEL_ID = "gemma3-1b-it-q4"
        private const val ONE_PIXEL_IMAGE_BASE64 =
            "Qk02AAAAAAAAADYAAAAoAAAAAQAAAAEAAAABABgAAAAAAAQAAAAAAAAAAAAAAAAAAAAAAAAA////AA=="
    }
}
