package com.example.kokoro82m.utils

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.content.ContentValues

class DatabaseHelper private constructor(context: Context) :
    SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS settings(key TEXT PRIMARY KEY, value TEXT)")
        db.execSQL("CREATE TABLE IF NOT EXISTS bookmarks(uri TEXT PRIMARY KEY, line INTEGER, position INTEGER)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("DROP TABLE IF EXISTS bookmarks")
            db.execSQL("CREATE TABLE IF NOT EXISTS bookmarks(uri TEXT PRIMARY KEY, line INTEGER, position INTEGER)")
        }
    }

    companion object {
        private const val DATABASE_NAME = "app.db"
        private const val DATABASE_VERSION = 2

        @Volatile private var instance: DatabaseHelper? = null

        fun getInstance(context: Context): DatabaseHelper {
            return instance ?: synchronized(this) {
                instance ?: DatabaseHelper(context.applicationContext).also { instance = it }
            }
        }
    }
}

object DatabaseManager {
    private fun helper(context: Context) = DatabaseHelper.getInstance(context)

    fun setSetting(context: Context, key: String, value: String) {
        val values = ContentValues().apply {
            put("key", key)
            put("value", value)
        }
        helper(context).writableDatabase.insertWithOnConflict(
            "settings", null, values, SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    fun getSetting(context: Context, key: String, default: String? = null): String? {
        val db = helper(context).readableDatabase
        db.query("settings", arrayOf("value"), "key=?", arrayOf(key), null, null, null)
            .use { cursor ->
                if (cursor.moveToFirst()) {
                    return cursor.getString(0)
                }
            }
        return default
    }

    fun setBookmark(context: Context, uri: String, line: Int, position: Int) {
        val values = ContentValues().apply {
            put("uri", uri)
            put("line", line)
            put("position", position)
        }
        helper(context).writableDatabase.insertWithOnConflict(
            "bookmarks", null, values, SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    fun getBookmark(context: Context, uri: String): Bookmark? {
        val db = helper(context).readableDatabase
        db.query("bookmarks", arrayOf("line", "position"), "uri=?", arrayOf(uri), null, null, null)
            .use { cursor ->
                if (cursor.moveToFirst()) {
                    val line = cursor.getInt(0)
                    val position = cursor.getInt(1)
                    return Bookmark(line, position)
                }
            }
        return null
    }

    fun clearBookmark(context: Context, uri: String) {
        helper(context).writableDatabase.delete("bookmarks", "uri=?", arrayOf(uri))
    }
}

