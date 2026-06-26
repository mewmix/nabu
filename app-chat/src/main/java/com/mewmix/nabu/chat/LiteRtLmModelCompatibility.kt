package com.mewmix.nabu.chat

import android.content.Context
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.mewmix.nabu.utils.DebugLogger
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

object LiteRtLmModelCompatibility {
    private const val PREFS_NAME = "litertlm_model_compatibility"

    data class Status(
        val supported: Boolean,
        val error: String? = null
    ) {
        val probed: Boolean get() = error != NOT_PROBED
    }

    data class Result(
        val cacheKey: String,
        val modelId: String,
        val liteRtLmVersion: String,
        val text: Status,
        val audio: Status,
        val vision: Status,
        val audioVision: Status
    ) {
        val supportsText: Boolean get() = text.supported
        val supportsAudio: Boolean get() = audio.supported
        val supportsVision: Boolean get() = vision.supported
        val supportsAudioAndVisionTogether: Boolean get() = audioVision.supported
        val supportsUsableVision: Boolean get() =
            supportsVision && (!supportsAudio || supportsAudioAndVisionTogether)
    }

    fun cachedResult(context: Context, modelId: String, modelPath: String): Result? {
        val key = cacheKey(modelId, modelPath)
        val raw = prefs(context).getString(key, null) ?: return null
        return runCatching { decode(key, raw) }
            .onFailure { DebugLogger.log("LiteRtLmModelCompatibility cache decode failed: ${it.message}") }
            .getOrNull()
    }

    fun cachedOrStatic(
        context: Context,
        modelId: String,
        modelPath: String,
        desiredAudio: Boolean,
        desiredVision: Boolean
    ): Result {
        cachedResult(context, modelId, modelPath)?.let { return it }
        return Result(
            cacheKey = cacheKey(modelId, modelPath),
            modelId = modelId,
            liteRtLmVersion = BuildConfig.LITERTLM_VERSION,
            text = Status(supported = true, error = NOT_PROBED),
            audio = Status(supported = desiredAudio, error = NOT_PROBED),
            vision = Status(supported = desiredVision, error = NOT_PROBED),
            audioVision = Status(supported = desiredAudio && desiredVision, error = NOT_PROBED)
        )
    }

    fun observeSuccessfulInitialization(
        context: Context,
        modelId: String,
        modelPath: String,
        audioEnabled: Boolean,
        visionEnabled: Boolean
    ): Result {
        val existing = cachedResult(context, modelId, modelPath)
        val result = mergeObservedStatus(
            existing = existing,
            modelId = modelId,
            modelPath = modelPath,
            text = Status(supported = true),
            audio = if (audioEnabled) Status(supported = true) else existing?.audio,
            vision = if (visionEnabled) Status(supported = true) else existing?.vision,
            audioVision = if (audioEnabled && visionEnabled) Status(supported = true) else existing?.audioVision
        )
        save(context, result)
        DebugLogger.log(
            "LiteRtLmModelCompatibility observed success modelId=$modelId text=${result.text.supported} " +
                "audio=${result.audio.supported} vision=${result.vision.supported} " +
                "audioVision=${result.audioVision.supported}"
        )
        return result
    }

    fun observeFailedInitialization(
        context: Context,
        modelId: String,
        modelPath: String,
        audioEnabled: Boolean,
        visionEnabled: Boolean,
        error: String
    ): Result {
        val existing = cachedResult(context, modelId, modelPath)
        val failed = Status(supported = false, error = error)
        val result = mergeObservedStatus(
            existing = existing,
            modelId = modelId,
            modelPath = modelPath,
            audio = if (audioEnabled && !visionEnabled) failed else existing?.audio,
            vision = if (visionEnabled && !audioEnabled) failed else existing?.vision,
            audioVision = if (audioEnabled && visionEnabled) failed else existing?.audioVision
        )
        save(context, result)
        DebugLogger.log(
            "LiteRtLmModelCompatibility observed failure modelId=$modelId audio=$audioEnabled " +
                "vision=$visionEnabled error=$error"
        )
        return result
    }

    fun probeAll(
        context: Context,
        modelId: String,
        modelPath: String,
        backend: Backend
    ): Result {
        val key = cacheKey(modelId, modelPath)
        val result = Result(
            cacheKey = key,
            modelId = modelId,
            liteRtLmVersion = BuildConfig.LITERTLM_VERSION,
            text = probeMode(context, modelPath, backend, enableAudio = false, enableVision = false),
            audio = probeMode(context, modelPath, backend, enableAudio = true, enableVision = false),
            vision = probeMode(context, modelPath, backend, enableAudio = false, enableVision = true),
            audioVision = probeMode(context, modelPath, backend, enableAudio = true, enableVision = true)
        )
        save(context, result)
        DebugLogger.log(
            "LiteRtLmModelCompatibility probed modelId=$modelId text=${result.text.supported} " +
                "audio=${result.audio.supported} vision=${result.vision.supported} " +
                "audioVision=${result.audioVision.supported}"
        )
        return result
    }

    fun capabilitiesFor(context: Context, modelId: String, modelPath: String): Set<String>? {
        val cached = cachedResult(context, modelId, modelPath) ?: return null
        return buildSet {
            if (cached.supportsText) add("completion")
            if (cached.supportsAudio || cached.supportsUsableVision) add("multimodal")
            if (cached.supportsAudio) add("audio")
            if (cached.supportsUsableVision) add("image")
        }
    }

    private fun mergeObservedStatus(
        existing: Result?,
        modelId: String,
        modelPath: String,
        text: Status? = existing?.text,
        audio: Status? = existing?.audio,
        vision: Status? = existing?.vision,
        audioVision: Status? = existing?.audioVision
    ): Result {
        return Result(
            cacheKey = cacheKey(modelId, modelPath),
            modelId = modelId,
            liteRtLmVersion = BuildConfig.LITERTLM_VERSION,
            text = text ?: Status(supported = false, error = NOT_PROBED),
            audio = audio ?: Status(supported = false, error = NOT_PROBED),
            vision = vision ?: Status(supported = false, error = NOT_PROBED),
            audioVision = audioVision ?: Status(supported = false, error = NOT_PROBED)
        )
    }

    private fun save(context: Context, result: Result) {
        prefs(context).edit().putString(result.cacheKey, encode(result)).apply()
    }

    private fun probeMode(
        context: Context,
        modelPath: String,
        backend: Backend,
        enableAudio: Boolean,
        enableVision: Boolean
    ): Status {
        return runCatching {
            val config = EngineConfig(
                modelPath = modelPath,
                backend = backend,
                audioBackend = if (enableAudio) backend else null,
                visionBackend = if (enableVision) backend else null,
                maxNumTokens = null,
                maxNumImages = if (enableVision) 1 else null,
                cacheDir = context.cacheDir.absolutePath
            )
            Engine(config).use { engine ->
                engine.initialize()
            }
            Status(supported = true)
        }.getOrElse { error ->
            Status(
                supported = false,
                error = error.message ?: error::class.java.simpleName
            )
        }
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private const val NOT_PROBED = "not_probed"

    private fun cacheKey(modelId: String, modelPath: String): String {
        val file = File(modelPath)
        val identity = listOf(
            modelId,
            file.absolutePath,
            file.length().toString(),
            file.lastModified().toString(),
            BuildConfig.LITERTLM_VERSION
        ).joinToString("|")
        return sha256(identity)
    }

    private fun encode(result: Result): String {
        return JSONObject()
            .put("model_id", result.modelId)
            .put("litertlm_version", result.liteRtLmVersion)
            .put("text", encodeStatus(result.text))
            .put("audio", encodeStatus(result.audio))
            .put("vision", encodeStatus(result.vision))
            .put("audio_vision", encodeStatus(result.audioVision))
            .toString()
    }

    private fun decode(cacheKey: String, raw: String): Result {
        val json = JSONObject(raw)
        return Result(
            cacheKey = cacheKey,
            modelId = json.optString("model_id"),
            liteRtLmVersion = json.optString("litertlm_version"),
            text = decodeStatus(json.getJSONObject("text")),
            audio = decodeStatus(json.getJSONObject("audio")),
            vision = decodeStatus(json.getJSONObject("vision")),
            audioVision = decodeStatus(json.getJSONObject("audio_vision"))
        )
    }

    private fun encodeStatus(status: Status): JSONObject =
        JSONObject()
            .put("supported", status.supported)
            .put("error", status.error)

    private fun decodeStatus(json: JSONObject): Status =
        Status(
            supported = json.optBoolean("supported", false),
            error = json.optString("error").takeIf { it.isNotBlank() && it != "null" }
        )

    private fun sha256(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}
