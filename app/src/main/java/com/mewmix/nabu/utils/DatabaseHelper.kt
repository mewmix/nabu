package com.mewmix.nabu.utils

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class DatabaseHelper(context: Context) : SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    companion object {
        private const val DATABASE_NAME = "kokoro.db"
        private const val DATABASE_VERSION = 5
        const val TABLE_PROJECTS = "projects"
        const val COLUMN_ID = "_id"
        const val COLUMN_URI = "uri"
        const val COLUMN_NAME = "name"
        const val COLUMN_STYLES = "styles"
        const val COLUMN_WEIGHTS = "weights"
        const val COLUMN_MODE = "mode"
        const val COLUMN_SPEED = "speed"
        const val COLUMN_BOOKMARK_LINE = "bookmark_line"
        const val COLUMN_AUDIO_PATH = "audio_path"
        const val COLUMN_USE_PREGENERATED = "use_pregenerated"

        const val TABLE_AUDIO_LINES = "audio_lines"
        const val COLUMN_LINE_INDEX = "line_index"
        const val COLUMN_FILE_PATH = "file_path"

        const val TABLE_SETTINGS = "settings"
        const val COLUMN_KEY = "key"
        const val COLUMN_VALUE = "value"

        const val TABLE_CONVERSATIONS = "conversations"
        const val COLUMN_TITLE = "title"
        const val COLUMN_MODEL_ID = "model_id"
        const val COLUMN_MESSAGES = "messages"
        const val COLUMN_CREATED_AT = "created_at"
        const val COLUMN_UPDATED_AT = "updated_at"
    }

    override fun onCreate(db: SQLiteDatabase) {
        val createProjectsTable = "CREATE TABLE $TABLE_PROJECTS (" +
                "$COLUMN_ID INTEGER PRIMARY KEY AUTOINCREMENT," +
                "$COLUMN_URI TEXT UNIQUE," +
                "$COLUMN_NAME TEXT," +
                "$COLUMN_STYLES TEXT," +
                "$COLUMN_WEIGHTS TEXT," +
                "$COLUMN_MODE TEXT," +
                "$COLUMN_SPEED REAL," +
                "$COLUMN_BOOKMARK_LINE INTEGER," +
                "$COLUMN_AUDIO_PATH TEXT," +
                "$COLUMN_USE_PREGENERATED INTEGER)"
        db.execSQL(createProjectsTable)

        val createAudioLinesTable = "CREATE TABLE $TABLE_AUDIO_LINES (" +
                "$COLUMN_ID INTEGER PRIMARY KEY AUTOINCREMENT," +
                "$COLUMN_URI TEXT," +
                "$COLUMN_LINE_INDEX INTEGER," +
                "$COLUMN_FILE_PATH TEXT," +
                "UNIQUE($COLUMN_URI, $COLUMN_LINE_INDEX))"
        db.execSQL(createAudioLinesTable)

        val createSettingsTable = "CREATE TABLE $TABLE_SETTINGS (" +
                "$COLUMN_ID INTEGER PRIMARY KEY AUTOINCREMENT," +
                "$COLUMN_KEY TEXT UNIQUE," +
                "$COLUMN_VALUE TEXT)"
        db.execSQL(createSettingsTable)

        val createConversationsTable = "CREATE TABLE $TABLE_CONVERSATIONS (" +
                "$COLUMN_ID INTEGER PRIMARY KEY AUTOINCREMENT," +
                "$COLUMN_TITLE TEXT NOT NULL," +
                "$COLUMN_MODEL_ID TEXT," +
                "$COLUMN_MESSAGES TEXT NOT NULL," +
                "$COLUMN_CREATED_AT INTEGER NOT NULL," +
                "$COLUMN_UPDATED_AT INTEGER NOT NULL)"
        db.execSQL(createConversationsTable)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 4) {
            db.execSQL("DROP TABLE IF EXISTS $TABLE_PROJECTS")
            db.execSQL("DROP TABLE IF EXISTS $TABLE_SETTINGS")
            db.execSQL("DROP TABLE IF EXISTS $TABLE_AUDIO_LINES")
            db.execSQL("DROP TABLE IF EXISTS $TABLE_CONVERSATIONS")
            onCreate(db)
            return
        }

        if (oldVersion < 5) {
            val createConversationsTable = "CREATE TABLE IF NOT EXISTS $TABLE_CONVERSATIONS (" +
                    "$COLUMN_ID INTEGER PRIMARY KEY AUTOINCREMENT," +
                    "$COLUMN_TITLE TEXT NOT NULL," +
                    "$COLUMN_MODEL_ID TEXT," +
                    "$COLUMN_MESSAGES TEXT NOT NULL," +
                    "$COLUMN_CREATED_AT INTEGER NOT NULL," +
                    "$COLUMN_UPDATED_AT INTEGER NOT NULL)"
            db.execSQL(createConversationsTable)
        }
    }
}