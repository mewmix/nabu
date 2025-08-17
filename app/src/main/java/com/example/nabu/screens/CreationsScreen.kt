package com.example.nabu.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.nabu.utils.Creation
import com.example.nabu.utils.loadCreations
import com.example.nabu.utils.playCreation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.mewmix.nabu.ui.brutalist.BrutalButton
import com.mewmix.nabu.ui.brutalist.PanelBox

@Composable
fun CreationsScreen() {
    val context = LocalContext.current
    var creations by remember { mutableStateOf<List<Creation>>(emptyList()) }
    var playingUri by remember { mutableStateOf<android.net.Uri?>(null) }
    var mediaPlayer by remember { mutableStateOf<android.media.MediaPlayer?>(null) }

    LaunchedEffect(Unit) {
        creations = withContext(Dispatchers.IO) { loadCreations(context) }
    }

    PanelBox(
        title = "Creations",
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(creations) { creation ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = creation.name,
                        modifier = Modifier.weight(1f)
                    )
                    val isPlaying = playingUri == creation.uri && mediaPlayer?.isPlaying == true
                    BrutalButton(onClick = {
                        if (isPlaying) {
                            mediaPlayer?.pause()
                        } else {
                            mediaPlayer?.release()
                            mediaPlayer = playCreation(context, creation.uri) {
                                playingUri = null
                            }
                            playingUri = creation.uri
                        }
                    }) {
                        Text(if (isPlaying) "Pause" else "Play")
                    }
                }
            }
        }
    }
}
