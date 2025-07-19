package com.example.kokoro82m.screens

import ai.onnxruntime.OrtSession
import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.kokoro82m.utils.*
import com.example.kokoro82m.viewmodel.BookViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookScreen(
    session: OrtSession,
    phonemeConverter: PhonemeConverter,
    bookViewModel: BookViewModel = viewModel()
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val lines by bookViewModel.lines.collectAsState()
    val currentLine by bookViewModel.currentLine.collectAsState()
    val playerState by bookViewModel.playerState.collectAsState()

    val bookUri by bookViewModel.bookUri.collectAsState()
    var bookmarkLine by remember { mutableIntStateOf(-1) }

    var playJob by remember { mutableStateOf<Job?>(null) }

    val listState = rememberLazyListState()

    val styleLoader = remember { StyleLoader(context) }
    var selectedStyles by remember { mutableStateOf(listOf("af_sarah")) }
    var weights by remember { mutableStateOf(mapOf("af_sarah" to 1f)) }
    var interpolationMode by remember { mutableStateOf(InterpolationMode.LINEAR) }
    var speed by remember { mutableFloatStateOf(SettingsManager.getSpeed(context)) }
    var debugMessage by remember { mutableStateOf<String?>(null) }
    var isProcessing by remember { mutableStateOf(false) }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let {
            bookViewModel.loadBook(context, it)
        }
    }

    LaunchedEffect(bookUri) {
        bookUri?.let {
            bookmarkLine = BookmarkManager.load(context, it.toString())
            if (bookmarkLine != -1) {
                bookViewModel.setCurrentLine(bookmarkLine)
            } else {
                bookViewModel.setCurrentLine(0)
            }
        }
    }

    LaunchedEffect(currentLine) {
        if (currentLine >= 0) {
            listState.animateScrollToItem(currentLine)
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        state = listState
    ) {
        item {
            StyleSelector(
                styleNames = styleLoader.names,
                selectedStyles = selectedStyles,
                onAddStyle = { style ->
                    selectedStyles = selectedStyles + style
                    weights = weights + (style to 1f)
                },
                onRemoveStyle = { style ->
                    selectedStyles = selectedStyles - style
                    weights = weights - style
                }
            )
        }

        item {
            WeightSliders(
                selectedStyles = selectedStyles,
                weights = weights,
                onWeightChanged = { style, value ->
                    weights = weights.toMutableMap().apply { put(style, value) }
                }
            )
        }

        item {
            InterpolationModeSelector(
                currentMode = interpolationMode,
                onModeSelected = { interpolationMode = it }
            )
        }

        item {
            Text("Speed: $speed")
        }

        item {
            Slider(
                value = speed,
                onValueChange = {
                    speed = it
                    SettingsManager.setSpeed(context, it)
                },
                valueRange = 0.5f..2.0f,
                steps = 5,
                modifier = Modifier.fillMaxWidth()
            )
        }

        if (bookmarkLine >= 0 && playerState == PlayerState.IDLE) {
            item {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("Resume at line ${bookmarkLine + 1}")
                    Button(onClick = { bookViewModel.setCurrentLine(bookmarkLine) }) {
                        Text("Go")
                    }
                    Button(onClick = {
                        bookUri?.let { BookmarkManager.clear(context, it.toString()) }
                        bookmarkLine = -1
                        bookViewModel.setCurrentLine(-1)
                    }) {
                        Text("Clear")
                    }
                }
            }
        }
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Button(onClick = { launcher.launch(arrayOf("text/plain")) }, modifier = Modifier.weight(1f)) {
                    Text("Open")
                }
                Button(
                    onClick = {
                        if (playerState == PlayerState.PLAYING) {
                            bookViewModel.audioPlayer.pause()
                        } else {
                            playJob?.cancel()
                            playJob = playBook(
                                scope = scope,
                                session = session,
                                phonemeConverter = phonemeConverter,
                                styleLoader = styleLoader,
                                selectedStyles = selectedStyles,
                                weights = weights,
                                mode = interpolationMode,
                                speed = speed,
                                lines = lines,
                                startLine = currentLine.coerceAtLeast(0),
                                bookUri = bookUri,
                                audioPlayer = bookViewModel.audioPlayer,
                                context = context,
                                onLineChanged = { bookViewModel.setCurrentLine(it) },
                                onFinished = {
                                    bookUri?.let { u -> BookmarkManager.clear(context, u.toString()) }
                                    bookmarkLine = -1
                                }
                            )
                        }
                    },
                    enabled = lines.isNotEmpty(),
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        when (playerState) {
                            PlayerState.IDLE -> "Play"
                            PlayerState.PLAYING -> "Pause"
                            PlayerState.PAUSED -> "Resume"
                        }
                    )
                }
                Button(
                    onClick = {
                        bookViewModel.audioPlayer.stop()
                        playJob?.cancel()
                    },
                    enabled = playerState != PlayerState.IDLE,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Stop")
                }
                Button(
                    onClick = {
                        isProcessing = true
                        scope.launch(Dispatchers.IO) {
                            try {
                                debugMessage = null
                                val mixedVector = mixStyles(
                                    styleLoader = styleLoader,
                                    styles = selectedStyles,
                                    weights = weights,
                                    mode = interpolationMode
                                )
                                val audioData = mutableListOf<Float>()
                                for (line in lines) {
                                    val phonemes = phonemeConverter.phonemize(line)
                                    val (audio, _) = createAudioFromStyleVector(
                                        phonemes = phonemes,
                                        voice = mixedVector,
                                        speed = speed,
                                        session = session
                                    )
                                    audioData.addAll(audio.toList())
                                }
                                val fileName = buildStyleFileName(selectedStyles, weights, interpolationMode)
                                saveAudio(audioData.toFloatArray(), context, fileName)
                            } catch (e: Exception) {
                                withContext(Dispatchers.Main) {
                                    debugMessage = e.localizedMessage
                                }
                            } finally {
                                withContext(Dispatchers.Main) {
                                    isProcessing = false
                                }
                            }
                        }
                    },
                    enabled = lines.isNotEmpty() && !isProcessing,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(if (isProcessing) "Saving..." else "Save")
                }
            }
        }

        itemsIndexed(lines) { index, line ->
            Text(
                text = line,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        bookViewModel.setCurrentLine(index)
                        playJob?.cancel() // Stop any existing playback
                        playJob = playBook(
                            scope = scope,
                            session = session,
                            phonemeConverter = phonemeConverter,
                            styleLoader = styleLoader,
                            selectedStyles = selectedStyles,
                            weights = weights,
                            mode = interpolationMode,
                            speed = speed,
                            lines = lines,
                            startLine = index,
                            bookUri = bookUri,
                            audioPlayer = bookViewModel.audioPlayer,
                            context = context,
                            onLineChanged = { bookViewModel.setCurrentLine(it) },
                            onFinished = {
                                bookUri?.let { u -> BookmarkManager.clear(context, u.toString()) }
                                bookmarkLine = -1
                            }
                        )
                    }
                    .background(if (index == currentLine) Color.Yellow else Color.Transparent)
                    .padding(4.dp)
            )
        }

        item {
            debugMessage?.let {
                Text(
                    text = it,
                    color = Color.Red,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        }

        item {
            if (SettingsManager.isDebug(context)) {
                val logs = DebugLogger.getLogs().joinToString("\n")
                Text(logs, modifier = Modifier.padding(top = 8.dp))
            }
        }
    }
}

private suspend fun playAudio(
    line: String,
    styleVector: Array<FloatArray>,
    speed: Float,
    session: OrtSession,
    phonemeConverter: PhonemeConverter,
    audioPlayer: AudioPlayer
) {
    try {
        val phonemes = phonemeConverter.phonemize(line)
        val (audio, _) = createAudioFromStyleVector(
            phonemes = phonemes,
            voice = styleVector,
            speed = speed,
            session = session
        )
        audioPlayer.prepare(audio)
        audioPlayer.playBlocking()
    } catch (e: Exception) {
        DebugLogger.log("playAudio failed: ${e.localizedMessage}")
    }
}

private fun playBook(
    scope: CoroutineScope,
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
    audioPlayer: AudioPlayer,
    context: Context,
    onLineChanged: (Int) -> Unit,
    onFinished: () -> Unit
): Job {
    return scope.launch(Dispatchers.IO) {
        var completed = true
        try {
            val mixedVector = mixStyles(
                styleLoader = styleLoader,
                styles = selectedStyles,
                weights = weights,
                mode = mode
            )
            for (index in startLine until lines.size) {
                if (!isActive) {
                    completed = false
                    break
                }

                withContext(Dispatchers.Main) {
                    onLineChanged(index)
                }
                bookUri?.let { BookmarkManager.save(context, it.toString(), index) }
                val line = lines[index]

                playAudio(
                    line = line,
                    styleVector = mixedVector,
                    speed = speed,
                    session = session,
                    phonemeConverter = phonemeConverter,
                    audioPlayer = audioPlayer
                )

                if (audioPlayer.getState() == PlayerState.PAUSED) {
                    completed = false
                    break
                }
            }
        } catch (e: Exception) {
            completed = false
            DebugLogger.log("playBook failed: ${e.localizedMessage}")
        } finally {
            withContext(Dispatchers.Main) {
                if (audioPlayer.getState() != PlayerState.PAUSED) {
                    onLineChanged(-1)
                    if (completed) {
                        onFinished()
                    }
                    audioPlayer.stop()
                }
            }
        }
    }
}

