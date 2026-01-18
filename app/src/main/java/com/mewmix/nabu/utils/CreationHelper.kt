package com.mewmix.nabu.utils

import android.content.ContentUris
import android.content.Context
import android.media.MediaPlayer
import android.net.Uri
import android.provider.MediaStore

data class Creation(val uri: Uri, val name: String)

fun loadCreations(context: Context): List<Creation> {
    val creations = mutableListOf<Creation>()
    val projection = arrayOf(
        MediaStore.Audio.Media._ID,
        MediaStore.Audio.Media.DISPLAY_NAME
    )
    val selection = "${MediaStore.Audio.Media.DISPLAY_NAME} LIKE ?"
    val selectionArgs = arrayOf("KOKORO_%")
    val sortOrder = "${MediaStore.Audio.Media.DATE_ADDED} DESC"
    val query = context.contentResolver.query(
        MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
        projection,
        selection,
        selectionArgs,
        sortOrder
    )
    query?.use { cursor ->
        val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
        val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
        while (cursor.moveToNext()) {
            val id = cursor.getLong(idColumn)
            val name = cursor.getString(nameColumn)
            val uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)
            creations.add(Creation(uri, name))
        }
    }
    return creations
}

fun playCreation(context: Context, uri: Uri, onComplete: () -> Unit): MediaPlayer {
    return MediaPlayer().apply {
        setDataSource(context, uri)
        setOnCompletionListener {
            release()
            onComplete()
        }
        prepare()
        start()
    }
}
