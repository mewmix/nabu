package com.example.nabu.speech

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import com.example.nabu.MainActivity
import com.example.nabu.data.ModelManager
import com.example.nabu.kokoro.KokoroEngine
import com.example.nabu.supertonic.DebugSupertonicEngine
import com.example.nabu.tts.BenchmarkingTTSEngine
import com.example.nabu.tts.TTSEngine
import com.example.nabu.tts.TTSManager
import com.example.nabu.utils.BenchmarkManager
import com.example.nabu.utils.DebugLogger
import com.example.nabu.utils.OnnxRuntimeManager
import com.example.nabu.utils.PhonemeConverter
import com.example.nabu.utils.SettingsManager
import com.example.nabu.utils.StyleLoader
import com.example.nabu.utils.createAudio
import com.example.nabu.utils.saveAudio
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.min

private const val NOTIFICATION_CHANNEL_ID = "speech_playback_channel"
private const val NOTIFICATION_ID = 2001

/**
 * Foreground service that manages TTS synthesis and playback in the background.
 * Provides a single queued pipeline: text -> chunks -> synthesis -> playback.
 */
class SpeechForegroundService : Service(), SpeechController {

    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private lateinit var phonemeConverter: PhonemeConverter
    private lateinit var styleLoader: StyleLoader
    private lateinit var audioFocusManager: AudioFocusManager

    private val _state = MutableStateFlow<SpeechState>(SpeechState.Idle)
    override val state: StateFlow<SpeechState> = _state.asStateFlow()

    // Bounded channel for audio chunks (max 4 chunks buffered)
    private val audioChannel = Channel<AudioChunk>(capacity = 4)

    private var synthJob: Job? = null
    private var playbackJob: Job? = null

    private var currentAudioTrack: AudioTrack? = null
    private val playbackMutex = Mutex()
    private var isUserPaused = false
    private var currentRequest: SpeechRequest? = null

    // Cache TTS engine to avoid "closed OrtSession" errors
    private var cachedEngine: TTSEngine? = null
    private val engineMutex = Mutex()
    
    inner class LocalBinder : Binder() {
        fun getService(): SpeechForegroundService = this@SpeechForegroundService
    }
    
    override fun onBind(intent: Intent?): IBinder {
        DebugLogger.log("SpeechService: Service bound")
        return binder
    }
    
    override fun onCreate() {
        super.onCreate()
        DebugLogger.log("SpeechService: Service created")

        phonemeConverter = PhonemeConverter(this)
        styleLoader = StyleLoader(this)

        audioFocusManager = AudioFocusManager(
            context = this,
            onFocusLost = { handleAudioFocusLost() },
            onFocusGained = { handleAudioFocusGained() }
        )

        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification("Ready"))

        // Initialize ONNX Runtime ONCE when service is created
        serviceScope.launch {
            try {
                DebugLogger.log("SpeechService: Initializing ONNX Runtime (one-time setup)")
                withContext(Dispatchers.IO) {
                    OnnxRuntimeManager.initialize(applicationContext)
                }
                DebugLogger.log("SpeechService: ONNX Runtime initialized successfully")
            } catch (e: Exception) {
                DebugLogger.log("SpeechService: Failed to initialize ONNX Runtime: ${e.message}")
            }
        }

        // Start playback worker
        startPlaybackWorker()
    }
    
    override fun onDestroy() {
        DebugLogger.log("SpeechService: Service destroyed")
        stop()
        audioFocusManager.abandonAudioFocus()

        // Close the cached engine properly
        cachedEngine?.close()
        cachedEngine = null
        DebugLogger.log("SpeechService: Closed TTS engine")

        serviceScope.cancel()
        super.onDestroy()
    }
    
    override fun speak(request: SpeechRequest) {
        DebugLogger.log("SpeechService: speak() called with text: ${request.text.take(50)}...")
        
        // Cancel any ongoing synthesis/playback
        stop()
        
        currentRequest = request
        isUserPaused = false
        
        // Request audio focus
        if (!audioFocusManager.requestAudioFocus()) {
            DebugLogger.log("SpeechService: Failed to acquire audio focus")
            _state.value = SpeechState.Error("Failed to acquire audio focus")
            return
        }
        
        // Start synthesis worker
        synthJob = serviceScope.launch {
            try {
                synthesizeAndEnqueue(request)
            } catch (e: Exception) {
                DebugLogger.log("SpeechService: Synthesis error: ${e.message}")
                _state.value = SpeechState.Error(e.message ?: "Synthesis failed")
            }
        }
    }
    
    override fun stop() {
        DebugLogger.log("SpeechService: stop() called")
        
        synthJob?.cancel()
        synthJob = null
        
        // Clear the audio channel
        while (!audioChannel.isEmpty) {
            audioChannel.tryReceive()
        }
        
        serviceScope.launch {
            playbackMutex.withLock {
                currentAudioTrack?.let {
                    it.stop()
                    it.release()
                    currentAudioTrack = null
                }
            }
        }
        
        isUserPaused = false
        currentRequest = null
        _state.value = SpeechState.Idle
        updateNotification("Ready")
    }
    
    override fun pause() {
        DebugLogger.log("SpeechService: pause() called")
        isUserPaused = true
        
        serviceScope.launch {
            playbackMutex.withLock {
                currentAudioTrack?.pause()
            }
        }
        
        val currentState = _state.value
        if (currentState is SpeechState.Playing) {
            _state.value = SpeechState.Paused(currentState.currentChunk, currentState.totalChunks)
            updateNotification("Paused")
        }
    }
    
    override fun resume() {
        DebugLogger.log("SpeechService: resume() called")
        
        if (!isUserPaused) return
        
        isUserPaused = false
        
        serviceScope.launch {
            playbackMutex.withLock {
                currentAudioTrack?.play()
            }
        }
        
        val currentState = _state.value
        if (currentState is SpeechState.Paused) {
            _state.value = SpeechState.Playing(currentState.currentChunk, currentState.totalChunks)
            updateNotification("Playing")
        }
    }
    
    private fun handleAudioFocusLost() {
        DebugLogger.log("SpeechService: Audio focus lost, pausing")
        pause()
    }
    
    private fun handleAudioFocusGained() {
        DebugLogger.log("SpeechService: Audio focus gained, resuming if not user-paused")
        if (!isUserPaused) {
            resume()
        }
    }
    
    private suspend fun synthesizeAndEnqueue(request: SpeechRequest) {
        DebugLogger.log("SpeechService: Starting synthesis")
        _state.value = SpeechState.PreparingModels
        updateNotification("Preparing models...")

        // Get or create cached TTS engine - bypass TTSManager to avoid session closure
        // NOTE: ONNX Runtime is initialized once in onCreate(), NOT here
        val engine = engineMutex.withLock {
            if (cachedEngine == null) {
                DebugLogger.log("SpeechService: Creating new TTS engine (first use)")
                try {
                    // Get engine directly without reinitializing (already done in onCreate)
                    val kokoroEngine = OnnxRuntimeManager.getEngine()
                    cachedEngine = BenchmarkingTTSEngine(kokoroEngine)
                    DebugLogger.log("SpeechService: Created Kokoro engine (session will persist)")
                } catch (e: Exception) {
                    DebugLogger.log("SpeechService: Failed to create engine: ${e.message}")
                    DebugLogger.log("SpeechService: Stack trace: ${e.stackTraceToString()}")
                }
            } else {
                DebugLogger.log("SpeechService: Reusing cached TTS engine (session intact)")
            }
            cachedEngine
        }

        if (engine == null) {
            _state.value = SpeechState.Error("No TTS engine available")
            updateNotification("Error: No engine")
            return
        }
        
        // Chunk the text
        val chunks = TextChunker.chunkText(request.text)
        if (chunks.isEmpty()) {
            _state.value = SpeechState.Error("No text to synthesize")
            updateNotification("Error: No text")
            return
        }
        
        DebugLogger.log("SpeechService: Chunked text into ${chunks.size} chunks")
        _state.value = SpeechState.Chunking(chunks.size)
        updateNotification("Chunking (${chunks.size} chunks)...")
        
        // Synthesize each chunk
        val rawEngine = if (engine is BenchmarkingTTSEngine) engine.delegate else engine
        
        chunks.forEachIndexed { index, chunk ->
            if (synthJob?.isActive != true) {
                DebugLogger.log("SpeechService: Synthesis cancelled")
                return
            }
            
            DebugLogger.log("SpeechService: Synthesizing chunk ${index + 1}/${chunks.size}")
            _state.value = SpeechState.Synthesizing(index + 1, chunks.size)
            updateNotification("Synthesizing ${index + 1}/${chunks.size}")
            
            try {
                val ttsStart = SystemClock.elapsedRealtime()
                val phonemes = withContext(Dispatchers.IO) {
                    phonemeConverter.phonemize(chunk)
                }
                
                val audioResult: Pair<FloatArray, Int> = withContext(Dispatchers.IO) {
                    if (rawEngine is KokoroEngine) {
                        createAudio(
                            phonemes = phonemes,
                            voice = request.style,
                            speed = request.speed,
                            engine = rawEngine,
                            styleLoader = styleLoader
                        )
                    } else if (rawEngine is DebugSupertonicEngine) {
                        rawEngine.setStyle(request.style)
                        val result = rawEngine.synthesize(chunk, request.speed)
                        result.wav to result.sampleRate
                    } else {
                        val result = engine.synthesize(chunk, request.speed)
                        result.wav to result.sampleRate
                    }
                }

                val audioData = audioResult.first
                val sampleRate = audioResult.second
                
                val genMs = SystemClock.elapsedRealtime() - ttsStart
                val audioMs = audioData.size * 1000L / sampleRate
                DebugLogger.log("SpeechService: Chunk ${index + 1}/${chunks.size} synthesized in ${genMs}ms (${audioMs}ms audio)")

                if (SettingsManager.isBenchmark(applicationContext)) {
                    BenchmarkManager.recordTts(OnnxRuntimeManager.currentBundle(), genMs, audioMs)
                }

                // Enqueue for playback (non-blocking - synthesis continues in parallel)
                DebugLogger.log("SpeechService: [SYNTH] Sending chunk ${index + 1}/${chunks.size} to playback queue...")
                audioChannel.send(AudioChunk(index + 1, chunks.size, audioData, sampleRate, request.shouldSave, request.style))
                DebugLogger.log("SpeechService: [SYNTH] Chunk ${index + 1} queued, continuing synthesis...")

                // NOTE: Synthesis continues immediately - playback worker runs in parallel coroutine
                
            } catch (e: Exception) {
                DebugLogger.log("SpeechService: Error synthesizing chunk ${index + 1}: ${e.message}")
                _state.value = SpeechState.Error("Synthesis failed: ${e.message}")
                updateNotification("Error")
                return
            }
        }
        
        DebugLogger.log("SpeechService: All chunks synthesized")
    }
    
    /**
     * Playback worker runs in parallel with synthesis worker.
     * It consumes chunks from audioChannel as they become available.
     * This allows synthesis to continue while previous chunks are playing.
     */
    private fun startPlaybackWorker() {
        playbackJob = serviceScope.launch {
            DebugLogger.log("SpeechService: [PLAYBACK] Worker started and waiting for chunks...")

            for (chunk in audioChannel) {
                val startTime = SystemClock.elapsedRealtime()
                DebugLogger.log("SpeechService: [PLAYBACK] ▶ Received chunk ${chunk.index}/${chunk.totalChunks}")

                // Wait if user paused
                while (isUserPaused) {
                    kotlinx.coroutines.delay(100)
                }

                _state.value = SpeechState.Playing(chunk.index, chunk.totalChunks)
                updateNotification("Playing ${chunk.index}/${chunk.totalChunks}")

                DebugLogger.log("SpeechService: [PLAYBACK] ▶ Playing chunk ${chunk.index} (synthesis continues in parallel)...")
                playAudioChunk(chunk)

                val playTime = SystemClock.elapsedRealtime() - startTime
                DebugLogger.log("SpeechService: [PLAYBACK] ▶ Finished chunk ${chunk.index} (took ${playTime}ms, ready for next)")

                // Save if needed (only on last chunk)
                if (chunk.shouldSave && chunk.index == chunk.totalChunks) {
                    withContext(Dispatchers.IO) {
                        saveAudio(chunk.audioData, applicationContext, chunk.style, chunk.sampleRate)
                    }
                }
            }

            DebugLogger.log("SpeechService: [PLAYBACK] Worker finished - all chunks played")
        }
    }
    
    private suspend fun playAudioChunk(chunk: AudioChunk) {
        playbackMutex.withLock {
            try {
                val channelConfig = AudioFormat.CHANNEL_OUT_MONO
                val audioFormat = AudioFormat.ENCODING_PCM_16BIT
                val bufferSize = AudioTrack.getMinBufferSize(chunk.sampleRate, channelConfig, audioFormat)
                
                currentAudioTrack = AudioTrack(
                    AudioManager.STREAM_MUSIC,
                    chunk.sampleRate,
                    channelConfig,
                    audioFormat,
                    bufferSize,
                    AudioTrack.MODE_STREAM
                )
                
                val byteBuffer = ByteBuffer.allocate(chunk.audioData.size * 2)
                byteBuffer.order(ByteOrder.LITTLE_ENDIAN)
                val shortBuffer = byteBuffer.asShortBuffer()
                
                for (sample in chunk.audioData) {
                    val pcmValue = (sample * Short.MAX_VALUE).toInt().toShort()
                    shortBuffer.put(pcmValue)
                }
                
                currentAudioTrack?.play()

                val pcmBytes = byteBuffer.array()
                val totalFrames = chunk.audioData.size
                val chunkSize = 4096
                var pos = 0

                val writeStart = SystemClock.elapsedRealtime()
                DebugLogger.log("SpeechService: [PLAYBACK] Writing ${pcmBytes.size} bytes to AudioTrack...")

                while (pos < pcmBytes.size && !isUserPaused) {
                    val remaining = pcmBytes.size - pos
                    val toWrite = min(chunkSize, remaining)
                    val written = currentAudioTrack?.write(pcmBytes, pos, toWrite) ?: 0
                    if (written > 0) {
                        pos += written
                    } else {
                        DebugLogger.log("SpeechService: [PLAYBACK] AudioTrack write failed (returned $written)")
                        break
                    }
                }

                val writeTime = SystemClock.elapsedRealtime() - writeStart
                DebugLogger.log("SpeechService: [PLAYBACK] Wrote $pos/${pcmBytes.size} bytes in ${writeTime}ms, now waiting for playback...")

                // Wait for all data to be played - use playback head position
                if (!isUserPaused && currentAudioTrack != null) {
                    val track = currentAudioTrack!!
                    val expectedDurationMs = (chunk.audioData.size * 1000L / chunk.sampleRate)
                    val startWaitTime = SystemClock.elapsedRealtime()
                    val maxWaitTime = expectedDurationMs + 2000 // Add 2s buffer

                    while (track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                        val elapsed = SystemClock.elapsedRealtime() - startWaitTime
                        if (elapsed > maxWaitTime) {
                            DebugLogger.log("SpeechService: Playback wait timeout after ${elapsed}ms (expected ${expectedDurationMs}ms)")
                            break
                        }
                        kotlinx.coroutines.delay(50)
                    }

                    val actualWaitTime = SystemClock.elapsedRealtime() - startWaitTime
                    DebugLogger.log("SpeechService: [PLAYBACK] Playback wait completed: ${actualWaitTime}ms (expected ${expectedDurationMs}ms)")
                }

                currentAudioTrack?.stop()
                currentAudioTrack?.release()
                currentAudioTrack = null

                DebugLogger.log("SpeechService: [PLAYBACK] AudioTrack released, chunk ${chunk.index}/${chunk.totalChunks} complete")

                // NOTE: Audio data is held in memory during playback (byteBuffer).
                // Writing happens in 4KB chunks which is fast (~1-5ms total).
                // AudioTrack handles internal buffering, so this doesn't block synthesis.
                
                // If this was the last chunk, go back to idle
                if (chunk.index == chunk.totalChunks && !isUserPaused) {
                    _state.value = SpeechState.Idle
                    updateNotification("Ready")
                    audioFocusManager.abandonAudioFocus()
                }
                
            } catch (e: Exception) {
                DebugLogger.log("SpeechService: Playback error: ${e.message}")
                _state.value = SpeechState.Error("Playback failed: ${e.message}")
                updateNotification("Error")
            }
        }
    }
    
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Speech Playback",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Ongoing speech synthesis and playback"
            }
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }
    
    private fun createNotification(contentText: String): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Nabu Speech")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }
    
    private fun updateNotification(contentText: String) {
        val notification = createNotification(contentText)
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(NOTIFICATION_ID, notification)
    }
    
    private data class AudioChunk(
        val index: Int,
        val totalChunks: Int,
        val audioData: FloatArray,
        val sampleRate: Int,
        val shouldSave: Boolean,
        val style: String
    )
}
