package com.example.nabu.utils

import android.content.Context
import android.util.Log
import com.example.nabu.R
import com.example.nabu.kokoro.Downloader
import com.example.nabu.kokoro.KokoroBundle
import com.example.nabu.kokoro.KokoroLoader
import com.example.nabu.kokoro.Manifest
import com.example.nabu.kokoro.ManifestProvider
import com.example.nabu.kokoro.RunEp
import com.example.nabu.kokoro.KokoroEngine
import com.example.nabu.tts.TTSEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

object OnnxRuntimeManager {
    private const val TAG = "OnnxRuntimeManager"
    private val mutex = Mutex()

    @Volatile
    private var bundle: KokoroBundle? = null
    @Volatile
    private var manifest: Manifest = ManifestProvider.kokoroV1()
    @Volatile
    private var engine: KokoroEngine? = null
    @Volatile
    private var status: RuntimeStatus? = null

    suspend fun initialize(
        context: Context,
        preferred: RunEp? = null,
        allowDownload: Boolean? = null,
        onProgress: (Downloader.DownloadProgress) -> Unit = {}
    ): Result<KokoroBundle> =
        mutex.withLock {
            val appContext = context.applicationContext
            manifest = ManifestProvider.kokoroV1()
            val choice = preferred ?: SettingsManager.getRuntimePreference(appContext)
            val downloadEnabled = allowDownload ?: SettingsManager.isKokoroAutoDownloadEnabled(appContext)

            val currentBundle = bundle
            val currentStatus = status
            if (currentBundle != null && currentStatus != null && currentStatus.ep == choice) {
                return@withLock Result.success(currentBundle)
            }

            if (downloadEnabled) {
                val fetchResult = Downloader.ensureModels(appContext, manifest, onProgress)
                if (fetchResult.isFailure) {
                    Log.w(TAG, "Failed to download Kokoro models", fetchResult.exceptionOrNull())
                    ensureBundledFallback(appContext, manifest)
                }
            } else if (!Downloader.modelsAvailable(appContext, manifest)) {
                ensureBundledFallback(appContext, manifest)
                if (!Downloader.modelsAvailable(appContext, manifest)) {
                    return@withLock Result.failure(
                        IllegalStateException("Kokoro voice models are not downloaded yet.")
                    )
                }
            }

            val loadResult = runCatching {
                KokoroLoader.load(appContext, manifest, choice)
            }

            loadResult.onSuccess { newBundle ->
                bundle?.session?.close()
                bundle = newBundle
                engine = KokoroEngine(newBundle, manifest)
                status = RuntimeStatus(newBundle.ep, newBundle.graphId, manifest.sampleRate)
                DebugLogger.log(
                    "Kokoro runtime ready ep=${newBundle.ep} graph=${newBundle.graphId} sampleRate=${manifest.sampleRate}"
                )
            }.onFailure { error ->
                Log.e(TAG, "Unable to load Kokoro session", error)
                DebugLogger.log("Kokoro runtime failed: ${error.message}")
            }

            loadResult
        }

    fun getEngine(): KokoroEngine =
        requireNotNull(engine) { "Kokoro engine not initialized" }

    fun currentBundle(): KokoroBundle? = bundle

    fun currentManifest(): Manifest = manifest

    fun runtimeStatus(): RuntimeStatus? = status

    fun close() {
        bundle?.session?.close()
        bundle = null
        engine = null
        status = null
    }

    private suspend fun ensureBundledFallback(context: Context, manifest: Manifest) {
        withContext(Dispatchers.IO) {
            val fallbackFile = manifest.files.firstOrNull { it.id == "kokoro_int8" } ?: return@withContext
            val dest = File(context.filesDir, fallbackFile.dest)
            if (dest.exists()) return@withContext

            runCatching {
                context.resources.openRawResource(R.raw.kokoro).use { input ->
                    dest.parentFile?.mkdirs()
                    FileOutputStream(dest).use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            val read = input.read(buffer)
                            if (read == -1) break
                            output.write(buffer, 0, read)
                        }
                    }
                }
                Log.i(TAG, "Provisioned bundled INT8 fallback to ${dest.absolutePath}")
            }.onFailure { error ->
                Log.w(TAG, "Bundled INT8 fallback unavailable", error)
            }
        }
    }

    data class RuntimeStatus(
        val ep: RunEp,
        val graphId: String,
        val sampleRate: Int
    ) {
        override fun toString(): String = "${ep.name}/${graphId}"
    }
}
