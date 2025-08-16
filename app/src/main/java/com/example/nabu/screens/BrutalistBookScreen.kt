package com.example.nabu.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.nabu.ui.components.ChapterList
import com.example.nabu.ui.components.RadialAudioVisualizer
import com.example.nabu.ui.components.SegmentedChapterProgress

/**
 * Demonstration screen showcasing the "Audio‑Brutalist" layout.
 * The UI is organised into three rigid vertical modules:
 *
 * 1. **Engine**   – Occupies the top half and hosts the central visualiser.
 * 2. **Console**  – Provides context and progress information.
 * 3. **Archive**  – Scrollable list of selectable chapters.
 */
@Composable
fun BrutalistBookScreen(
    chapterTitles: List<String>,
    currentChapter: Int,
    amplitudes: List<Float>,
    isPlaying: Boolean,
    onChapterSelected: (Int) -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        // Engine
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentAlignment = Alignment.Center
        ) {
            RadialAudioVisualizer(
                amplitudes = amplitudes,
                isPlaying = isPlaying,
                modifier = Modifier.fillMaxSize(0.8f)
            )
        }

        // Console
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = chapterTitles.getOrNull(currentChapter) ?: "",
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(modifier = Modifier.height(8.dp))
            SegmentedChapterProgress(
                totalChapters = chapterTitles.size,
                currentChapter = currentChapter
            )
        }

        // Archive
        ChapterList(
            chapters = chapterTitles,
            currentIndex = currentChapter,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 16.dp),
            onChapterSelected = onChapterSelected
        )
    }
}
