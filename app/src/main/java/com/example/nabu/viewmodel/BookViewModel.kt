package com.example.nabu.viewmodel

import android.app.Application
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.nabu.data.PlayableUnit
import com.example.nabu.utils.AudioPlayer
import com.example.nabu.utils.AudioPlayerManager
import com.example.nabu.utils.ChunkFeeder
import com.example.nabu.utils.DocumentReader
import com.example.nabu.utils.EpubWriter
import com.example.nabu.utils.InterpolationMode
import com.example.nabu.utils.KokoroAudioPlayer
import com.example.nabu.utils.PhonemeConverter
import com.example.nabu.utils.PlayerState
import com.example.nabu.utils.PlaybackNotification
import com.example.nabu.utils.StyleLoader
import com.example.nabu.utils.OnnxRuntimeManager
import com.example.nabu.utils.playBook
import com.example.nabu.tts.TTSManager
import com.example.nabu.data.ModelManager
import com.example.nabu.utils.DebugLogger
import com.example.nabu.utils.BookmarkManager
import com.example.nabu.utils.SettingsManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

class BookViewModel(private val app: Application) : AndroidViewModel(app) {
    private val _bookUri = MutableStateFlow<Uri?>(null)
    val bookUri = _bookUri.asStateFlow()

    private val _bookDisplayName = MutableStateFlow<String?>(null)
    val bookDisplayName = _bookDisplayName.asStateFlow()

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

    private fun initializeAudioPlayer() {
        _audioPlayer = KokoroAudioPlayer(viewModelScope) { state ->
            _playerState.value = state
            appContext?.let { PlaybackNotification.update(it, state) }
        }
    }

    private var playJob: Job? = null

    fun loadBook(context: Context, uri: Uri) {
        _bookUri.value = uri
        _bookDisplayName.value = resolveDisplayName(context, uri)
        SettingsManager.setLastBookUri(context, uri.toString())
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
        phonemeConverter: PhonemeConverter,
        styleLoader: StyleLoader,
        selectedStyles: List<String>,
        weights: Map<String, Float>,
        mode: InterpolationMode,
        speed: Float,
        units: List<PlayableUnit>,
        startUnit: Int,
        bookUri: Uri?,
        projectName: String,
        context: Context,
        usePregenerated: Boolean,
        onFinished: () -> Unit,
    ) {
        playJob?.cancel()
        appContext = context.applicationContext
        initializeAudioPlayer()
        AudioPlayerManager.player = audioPlayer
        AudioPlayerManager.onStop = { stopPlayback() }
        
        viewModelScope.launch(Dispatchers.IO) {
            val modelManager = ModelManager(context)
            val engine = TTSManager.getEngine(context, modelManager)

            if (engine == null) {
                DebugLogger.log("BookViewModel: No TTS engine available")
                return@launch
            }

            val target = if (projectName.isNotBlank()) {
                projectName
            } else {
                bookUri?.lastPathSegment ?: "unknown"
            }
            val style = selectedStyles.joinToString("+") { it.uppercase() }
            if (bookUri != null) {
                SettingsManager.setLastBookUri(context, bookUri.toString())
            }
            PlaybackNotification.show(appContext!!, true, target, style, bookUri?.toString())
            playJob = playBook(
                scope = viewModelScope,
                engine = engine,
                phonemeConverter = phonemeConverter,
                styleLoader = styleLoader,
                selectedStyles = selectedStyles,
                weights = weights,
                mode = mode,
                speed = speed,
                lines = units.map { it.text },
                startLine = startUnit,
                bookUri = bookUri,
                audioPlayer = audioPlayer,
                context = context,
                onLineChanged = { line ->
                    setCurrentUnitIndex(line)
                    if (bookUri != null) {
                        BookmarkManager.save(context, bookUri.toString(), line)
                    }
                },
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

    fun updatePlayableUnitText(index: Int, newText: String) {
        val currentUnits = _playableUnits.value
        if (index !in currentUnits.indices) return
        val updated = currentUnits.toMutableList()
        val existing = updated[index]
        updated[index] = existing.copy(text = newText)
        _playableUnits.value = updated
    }

    suspend fun saveEditedCopy(context: Context, uri: Uri, desiredName: String): Boolean {
        val units = _playableUnits.value
        if (units.isEmpty()) return false
        val displayName = buildEditedDisplayName(desiredName)
        val title = displayName.substringBeforeLast('.').ifBlank { "Edited Book" }
        val paragraphs = combineUnitsToParagraphs(units)
        return withContext(Dispatchers.IO) {
            EpubWriter.save(context, uri, title, paragraphs)
        }
    }

    private fun buildEditedDisplayName(desiredName: String): String {
        val trimmed = desiredName.trim().ifBlank { defaultEditedName() }
        return if (trimmed.lowercase(Locale.US).endsWith(".epub")) trimmed else "$trimmed.epub"
    }

    private fun defaultEditedName(): String {
        val base = _bookDisplayName.value?.substringBeforeLast('.')
        return ((base?.takeIf { it.isNotBlank() }) ?: "edited_book") + "_edited.epub"
    }

    private fun combineUnitsToParagraphs(units: List<PlayableUnit>): List<String> {
        if (units.isEmpty()) return emptyList()
        val grouped = units.groupBy { it.paragraphIndex }.toSortedMap()
        return grouped.values.map { unitGroup ->
            unitGroup
                .sortedBy { it.unitIndex }
                .joinToString(" ") { it.text.trim() }
                .replace(Regex("\\s+"), " ")
                .trim()
        }.flatMap { paragraph ->
            paragraph
                .split(Regex("\\n{2,}"))
                .map { it.trim() }
                .filter { it.isNotEmpty() }
        }
    }

    private fun resolveDisplayName(context: Context, uri: Uri): String {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val name = cursor.getString(0)
                if (!name.isNullOrBlank()) return name
            }
        }
        return uri.lastPathSegment ?: "document"
    }
}
