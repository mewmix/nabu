package com.example.kokoro82m.viewmodel

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import ai.onnxruntime.OrtSession
import com.example.kokoro82m.utils.AudioPlayer
import com.example.kokoro82m.utils.AudioPlayerManager
import com.example.kokoro82m.utils.InterpolationMode
import com.example.kokoro82m.utils.ChunkFeeder
import com.example.kokoro82m.utils.KittenAudioPlayer
import com.example.kokoro82m.utils.KokoroAudioPlayer
import com.example.kokoro82m.utils.PhonemeConverter
import com.example.kokoro82m.utils.PlayerState
import com.example.kokoro82m.utils.PlaybackNotification
import com.example.kokoro82m.utils.StyleLoader
import com.example.kokoro82m.utils.DocumentReader
import com.example.kokoro82m.utils.TtsEngine
import com.example.kokoro82m.utils.playBook
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class BookViewModel(private val app: Application) : AndroidViewModel(app) {
    private val _bookUri = MutableStateFlow<Uri?>(null)
    val bookUri = _bookUri.asStateFlow()

    private val _lines = MutableStateFlow<List<String>>(emptyList())
    val lines = _lines.asStateFlow()

    private val _currentLine = MutableStateFlow(-1)
    val currentLine = _currentLine.asStateFlow()

    private val _playerState = MutableStateFlow(PlayerState.IDLE)
    val playerState = _playerState.asStateFlow()

    private var appContext: Context? = null

    private var _audioPlayer: AudioPlayer? = null
    val audioPlayer: AudioPlayer
        get() = _audioPlayer!!

    private fun initializeAudioPlayer(engine: TtsEngine) {
        _audioPlayer = when (engine) {
            TtsEngine.KOKORO -> KokoroAudioPlayer(viewModelScope) { state ->
                _playerState.value = state
                appContext?.let { PlaybackNotification.update(it, state) }
            }
            TtsEngine.KITTEN -> KittenAudioPlayer(viewModelScope) { state ->
                _playerState.value = state
                appContext?.let { PlaybackNotification.update(it, state) }
            }
        }
    }

    private var playJob: Job? = null

    fun loadBook(context: Context, uri: Uri) {
        _bookUri.value = uri
        viewModelScope.launch(Dispatchers.IO) {
            val lines = DocumentReader
                .asFlow(context, uri, byLine = true, lineLength = 120)
                .chunks
                .toList()
            withContext(Dispatchers.Main) {
                _lines.value = lines
            }
        }
    }

    fun setCurrentLine(line: Int) {
        _currentLine.value = line
    }

    fun startPlayback(
        session: OrtSession,
        phonemeConverter: PhonemeConverter,
        styleLoader: StyleLoader,
        selectedStyles: List<String>,
        weights: Map<String, Float>,
        mode: InterpolationMode,
        speed: Float,
        lines: List<String>,
        startLine: Int,
        bookUri: Uri?,
        context: Context,
        engine: TtsEngine,
        usePregenerated: Boolean,
        onFinished: () -> Unit,
    ) {
        playJob?.cancel()
        appContext = context.applicationContext
        initializeAudioPlayer(engine)
        AudioPlayerManager.player = audioPlayer
        PlaybackNotification.show(appContext!!, true)
        playJob = playBook(
            scope = viewModelScope,
            session = session,
            phonemeConverter = phonemeConverter,
            styleLoader = styleLoader,
            selectedStyles = selectedStyles,
            weights = weights,
            mode = mode,
            speed = speed,
            lines = lines,
            startLine = startLine,
            bookUri = bookUri,
            audioPlayer = audioPlayer,
            context = context,
            onLineChanged = { setCurrentLine(it) },
            onFinished = onFinished,
            usePregenerated = usePregenerated,
        )
    }

    fun stopPlayback() {
        playJob?.cancel()
        playJob = null
        _audioPlayer?.stop()
        appContext?.let { PlaybackNotification.cancel(it) }
        AudioPlayerManager.player = null
    }

    fun openDocument(uri: Uri) {
        val result = DocumentReader.asFlow(app, uri, byLine = true, lineLength = 120)
        ChunkFeeder.start(viewModelScope, result.chunks)
    }

    fun stopReading() {
        ChunkFeeder.stop()
    }
}
