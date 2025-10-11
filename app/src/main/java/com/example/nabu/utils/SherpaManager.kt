package com.example.nabu.utils

import android.content.Context
import com.example.nabu.tts.sherpa.SherpaTtsEngine
import java.util.concurrent.atomic.AtomicReference

/**
 * Lightweight wrapper that owns a single instance of [SherpaTtsEngine] and rebuilds it
 * whenever user-facing configuration (lexicon usage or thread count) changes.
 */
object SherpaManager {
    private data class Config(
        val useLexicon: Boolean,
        val threads: Int,
    )

    private val engineRef = AtomicReference<SherpaTtsEngine?>()
    private val configRef = AtomicReference<Config?>()

    fun listVoices(context: Context): List<String> =
        getOrCreate(context).getAvailableVoices()

    fun synthesize(
        context: Context,
        text: String,
        voice: String,
        speed: Float,
    ): Pair<FloatArray, Int> {
        val engine = getOrCreate(context)
        val audio = engine.generate(text, voice, speed)
        val sampleRate = engine.getSampleRate()
        return audio to sampleRate
    }

    fun getSampleRate(context: Context): Int = getOrCreate(context).getSampleRate()

    fun invalidate() {
        engineRef.getAndSet(null)?.close()
        configRef.set(null)
    }

    private fun getOrCreate(context: Context): SherpaTtsEngine {
        val appContext = context.applicationContext
        val desired = Config(
            useLexicon = SettingsManager.isSherpaLexiconEnabled(appContext),
            threads = SettingsManager.getSherpaThreadCount(appContext),
        )
        val cachedConfig = configRef.get()
        val cachedEngine = engineRef.get()
        if (cachedEngine != null && cachedConfig == desired) {
            return cachedEngine
        }

        synchronized(SherpaManager) {
            val currentConfig = configRef.get()
            val currentEngine = engineRef.get()
            if (currentEngine != null && currentConfig == desired) {
                return currentEngine
            }
            currentEngine?.close()
            val engine = SherpaTtsEngine(
                context = appContext,
                lexiconFileName = if (desired.useLexicon) SherpaTtsEngine.DEFAULT_LEXICON_FILE else null,
                voiceToSpeakerId = DEFAULT_VOICE_MAP,
                maxThreads = desired.threads,
            )
            engineRef.set(engine)
            configRef.set(desired)
            return engine
        }
    }

    private val DEFAULT_VOICE_MAP =
        mapOf(SherpaTtsEngine.DEFAULT_VOICE_NAME to 0)
}
