package com.example.nabu.utils

import android.content.Context
import android.net.Uri
import com.example.nabu.data.PlayableUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

object DocumentReader {
    private const val SAFE_CHAR_LIMIT = 280

    fun asPlayableUnits(ctx: Context, uri: Uri): Flow<PlayableUnit> {
        val (textSequence, _) = TextExtractor.extract(ctx, uri, chunkSize = Int.MAX_VALUE)
        return flow {
            textSequence.forEachIndexed { paragraphIndex, paragraphText ->
                val sentences = paragraphText
                    .split(Regex("(?<=[.!?])\\s*"))
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }

                if (sentences.isEmpty()) return@forEachIndexed

                var unitIndexInParagraph = 0
                val sentenceBuffer = StringBuilder()

                sentences.forEach { sentence ->
                    if (sentenceBuffer.isNotEmpty() && sentenceBuffer.length + sentence.length > SAFE_CHAR_LIMIT) {
                        emit(
                            PlayableUnit(
                                text = sentenceBuffer.toString(),
                                paragraphIndex = paragraphIndex,
                                unitIndex = unitIndexInParagraph,
                            ),
                        )
                        sentenceBuffer.clear()
                        unitIndexInParagraph++
                    }

                    if (sentenceBuffer.isNotEmpty()) {
                        sentenceBuffer.append(" ")
                    }
                    sentenceBuffer.append(sentence)
                }

                if (sentenceBuffer.isNotEmpty()) {
                    emit(
                        PlayableUnit(
                            text = sentenceBuffer.toString(),
                            paragraphIndex = paragraphIndex,
                            unitIndex = unitIndexInParagraph,
                        ),
                    )
                }
            }
        }.flowOn(Dispatchers.IO)
    }
}
