package com.example.kokoro82m.utils

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

object DocumentReader {
    data class Result(val chunks: Flow<String>, val title: String)

    fun asFlow(ctx: Context, uri: Uri, chunkSize: Int = 1600): Result {
        val (seq, meta) = TextExtractor.extract(ctx, uri, chunkSize)
        val fl = flow {
            for (c in seq) emit(c)
        }.flowOn(Dispatchers.IO)
        return Result(chunks = fl, title = meta.displayName)
    }
}

