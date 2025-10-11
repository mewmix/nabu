package com.example.nabu.viewmodel

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import ai.onnxruntime.OrtSession
import com.example.nabu.data.PlayableUnit
import com.example.nabu.utils.AudioPlayer
import com.example.nabu.utils.AudioPlayerManager
import com.example.nabu.utils.ChunkFeeder
import com.example.nabu.utils.DocumentReader
import com.example.nabu.utils.InterpolationMode
import com.example.nabu.utils.KittenAudioPlayer
import com.example.nabu.utils.KokoroAudioPlayer
import com.example.nabu.utils.PhonemeConverter
import com.example.nabu.utils.PlayerState
import com.example.nabu.utils.PlaybackNotification
import com.example.nabu.utils.StyleLoader
import com.example.nabu.utils.TtsEngine
import com.example.nabu.utils.playBook
import com.example.nabu.utils.SherpaAudioPlayer
import com.example.nabu.utils.playBookSherpa
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class BookViewModel(private val app: Application) : AndroidViewModel(app) {
    private val _bookUri = MutableStateFlow<Uri?>(null)
    val bookUri = _bookUri.asStateFlow()

    private val _playableUnits = MutableStateFlow<List<PlayableUnit>>(emptyList())
    val playableUnits = _playableUnits.asStateFlow()

    private val _currentUnitIndex = MutableStateFlow(-1)
    val currentUnitIndex = _currentUnitIndex.asStateFlow()

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
            TtsEngine.SHERPA -> SherpaAudioPlayer(app.applicationContext, viewModelScope) { state ->
                _playerState.value = state
                appContext?.let { PlaybackNotification.update(it, state) }
            }
        }
    }

    private var playJob: Job? = null

    fun loadBook(context: Context, uri: Uri) {
        _bookUri.value = uri
        viewModelScope.launch(Dispatchers.IO) {
            val units = DocumentReader
                .asPlayableUnits(context, uri)
                .toList()
            withContext(Dispatchers.Main) {
                _playableUnits.value = units
            }
        }
    }

    fun setCurrentUnitIndex(index: Int) {
        _currentUnitIndex.value = index
    }

    fun startPlayback(
        session: OrtSession?,
        phonemeConverter: PhonemeConverter,
        styleLoader: StyleLoader?,
        selectedStyles: List<String>,
        weights: Map<String, Float>,
        mode: InterpolationMode,
        speed: Float,
        units: List<PlayableUnit>,
        startUnit: Int,
        bookUri: Uri?,
        projectName: String,
        context: Context,
        engine: TtsEngine,
        usePregenerated: Boolean,
        onFinished: () -> Unit,
    ) {
        playJob?.cancel()
        appContext = context.applicationContext
        initializeAudioPlayer(engine)
        AudioPlayerManager.player = audioPlayer
        AudioPlayerManager.onStop = { stopPlayback() }
        val target = if (projectName.isNotBlank()) {
            projectName
        } else {
            bookUri?.lastPathSegment ?: "unknown"
        }
        val style = selectedStyles.joinToString("+") { it.uppercase() }
        PlaybackNotification.show(appContext!!, true, target, style)
        playJob = if (engine == TtsEngine.SHERPA) {
            playBookSherpa(
                scope = viewModelScope,
                selectedStyles = selectedStyles,
                speed = speed,
                lines = units.map { it.text },
                startLine = startUnit,
                bookUri = bookUri,
                audioPlayer = audioPlayer,
                context = context,
                onLineChanged = { setCurrentUnitIndex(it) },
                onFinished = onFinished,
                usePregenerated = usePregenerated,
            )
        } else {
            val actualSession = session
                ?: throw IllegalStateException("ONNX session required for $engine playback")
            val loader = styleLoader ?: StyleLoader(context)
            playBook(
                scope = viewModelScope,
                session = actualSession,
                phonemeConverter = phonemeConverter,
                styleLoader = loader,
                selectedStyles = selectedStyles,
                weights = weights,
                mode = mode,
                speed = speed,
                lines = units.map { it.text },
                startLine = startUnit,
                bookUri = bookUri,
                audioPlayer = audioPlayer,
                context = context,
                onLineChanged = { setCurrentUnitIndex(it) },
                onFinished = onFinished,
                usePregenerated = usePregenerated,
            )
        }
    }

    fun stopPlayback() {
        playJob?.cancel()
        playJob = null
        _audioPlayer?.stop()
        appContext?.let { PlaybackNotification.cancel(it) }
        AudioPlayerManager.player = null
        AudioPlayerManager.onStop = null
    }

    fun openDocument(uri: Uri) {
        val flow = DocumentReader.asPlayableUnits(app, uri)
        ChunkFeeder.start(viewModelScope, flow.map { it.text })
    }

    fun stopReading() {
        ChunkFeeder.stop()
    }
}
