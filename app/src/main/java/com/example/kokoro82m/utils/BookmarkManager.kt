package com.example.kokoro82m.utils

import android.content.Context

object BookmarkManager {
    fun save(context: Context, uri: String, line: Int, position: Int) {
        DatabaseManager.setBookmark(context, uri, line, position)
    }

    fun load(context: Context, uri: String): Bookmark? {
        return DatabaseManager.getBookmark(context, uri)
    }

    fun clear(context: Context, uri: String) {
        DatabaseManager.clearBookmark(context, uri)
    }
}
