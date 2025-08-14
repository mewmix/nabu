package com.example.nabu.utils

import android.content.Context

object BookmarkManager {
    fun save(context: Context, uri: String, line: Int) {
        DatabaseManager.setBookmark(context, uri, line)
    }

    fun load(context: Context, uri: String): Bookmark? {
        return DatabaseManager.getBookmark(context, uri)
    }

    fun clear(context: Context, uri: String) {
        DatabaseManager.clearBookmark(context, uri)
    }
}
