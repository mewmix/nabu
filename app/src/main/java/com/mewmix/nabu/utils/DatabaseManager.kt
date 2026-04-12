package com.mewmix.nabu.utils

import android.content.ContentValues
import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File

object DatabaseManager {
    private var dbHelper: DatabaseHelper? = null

    private fun getHelper(context: Context): DatabaseHelper {
        return dbHelper ?: synchronized(this) {
            dbHelper ?: DatabaseHelper(context.applicationContext).also { dbHelper = it }
        }
    }

    fun setProject(context: Context, project: Project) {
        val db = getHelper(context).writableDatabase
        val gson = Gson()
        val stylesJson = gson.toJson(project.styles)
        val weightsJson = gson.toJson(project.weights)

        val values = ContentValues().apply {
            put(DatabaseHelper.COLUMN_URI, project.uri)
            put(DatabaseHelper.COLUMN_NAME, project.name)
            put(DatabaseHelper.COLUMN_STYLES, stylesJson)
            put(DatabaseHelper.COLUMN_WEIGHTS, weightsJson)
            put(DatabaseHelper.COLUMN_MODE, project.mode.name)
            put(DatabaseHelper.COLUMN_SPEED, project.speed)
            put(DatabaseHelper.COLUMN_USE_PREGENERATED, if (project.usePregenerated) 1 else 0)
            project.audioPath?.let { put(DatabaseHelper.COLUMN_AUDIO_PATH, it) }
            project.bookmark?.let {
                put(DatabaseHelper.COLUMN_BOOKMARK_LINE, it.line)
            } ?: put(DatabaseHelper.COLUMN_BOOKMARK_LINE, -1)
        }

        db.replace(DatabaseHelper.TABLE_PROJECTS, null, values)
    }

    fun getProject(context: Context, uri: String): Project? {
        val db = getHelper(context).readableDatabase
        val gson = Gson()
        val cursor = db.query(
            DatabaseHelper.TABLE_PROJECTS,
            null,
            "${DatabaseHelper.COLUMN_URI} = ?",
            arrayOf(uri),
            null,
            null,
            null
        )

        var project: Project? = null
        if (cursor.moveToFirst()) {
            val name = cursor.getString(cursor.getColumnIndexOrThrow(DatabaseHelper.COLUMN_NAME))
            val stylesJson = cursor.getString(cursor.getColumnIndexOrThrow(DatabaseHelper.COLUMN_STYLES))
            val weightsJson = cursor.getString(cursor.getColumnIndexOrThrow(DatabaseHelper.COLUMN_WEIGHTS))
            val stylesType = TypeToken.getParameterized(List::class.java, String::class.java).type
            val weightsType = TypeToken.getParameterized(
                Map::class.java,
                String::class.java,
                java.lang.Float::class.java
            ).type
            val styles = gson.fromJson<List<String>>(stylesJson, stylesType)
            val weights = gson.fromJson<Map<String, Float>>(weightsJson, weightsType)
            val mode = runCatching { InterpolationMode.valueOf(cursor.getString(cursor.getColumnIndexOrThrow(DatabaseHelper.COLUMN_MODE))) }.getOrDefault(InterpolationMode.LINEAR)
            val speed = cursor.getFloat(cursor.getColumnIndexOrThrow(DatabaseHelper.COLUMN_SPEED))
            val bookmarkLine = cursor.getInt(cursor.getColumnIndexOrThrow(DatabaseHelper.COLUMN_BOOKMARK_LINE))
            val bookmark = if (bookmarkLine != -1) Bookmark(bookmarkLine) else null
            val audioPathIndex = cursor.getColumnIndex(DatabaseHelper.COLUMN_AUDIO_PATH)
            val audioPath = if (audioPathIndex != -1) cursor.getString(audioPathIndex) else null
            val usePregeneratedIdx = cursor.getColumnIndex(DatabaseHelper.COLUMN_USE_PREGENERATED)
            val usePregenerated = if (usePregeneratedIdx != -1) cursor.getInt(usePregeneratedIdx) == 1 else false

            project = Project(uri, name, styles, weights, mode, speed, bookmark, audioPath, usePregenerated)
        }

        cursor.close()
        return project
    }

    fun getProjects(context: Context): List<Project> {
        val db = getHelper(context).readableDatabase
        val gson = Gson()
        val cursor = db.query(DatabaseHelper.TABLE_PROJECTS, null, null, null, null, null, null)
        val projects = mutableListOf<Project>()
        val stylesType = TypeToken.getParameterized(List::class.java, String::class.java).type
        val weightsType = TypeToken.getParameterized(
            Map::class.java,
            String::class.java,
            java.lang.Float::class.java
        ).type
        while (cursor.moveToNext()) {
            val uri = cursor.getString(cursor.getColumnIndexOrThrow(DatabaseHelper.COLUMN_URI))
            val name = cursor.getString(cursor.getColumnIndexOrThrow(DatabaseHelper.COLUMN_NAME))
            val stylesJson = cursor.getString(cursor.getColumnIndexOrThrow(DatabaseHelper.COLUMN_STYLES))
            val weightsJson = cursor.getString(cursor.getColumnIndexOrThrow(DatabaseHelper.COLUMN_WEIGHTS))
            val styles = gson.fromJson<List<String>>(stylesJson, stylesType)
            val weights = gson.fromJson<Map<String, Float>>(weightsJson, weightsType)
            val mode = runCatching { InterpolationMode.valueOf(cursor.getString(cursor.getColumnIndexOrThrow(DatabaseHelper.COLUMN_MODE))) }.getOrDefault(InterpolationMode.LINEAR)
            val speed = cursor.getFloat(cursor.getColumnIndexOrThrow(DatabaseHelper.COLUMN_SPEED))
            val bookmarkLine = cursor.getInt(cursor.getColumnIndexOrThrow(DatabaseHelper.COLUMN_BOOKMARK_LINE))
            val bookmark = if (bookmarkLine != -1) Bookmark(bookmarkLine) else null
            val audioPathIndex = cursor.getColumnIndex(DatabaseHelper.COLUMN_AUDIO_PATH)
            val audioPath = if (audioPathIndex != -1) cursor.getString(audioPathIndex) else null
            val usePregeneratedIdx = cursor.getColumnIndex(DatabaseHelper.COLUMN_USE_PREGENERATED)
            val usePregenerated = if (usePregeneratedIdx != -1) cursor.getInt(usePregeneratedIdx) == 1 else false
            projects.add(Project(uri, name, styles, weights, mode, speed, bookmark, audioPath, usePregenerated))
        }
        cursor.close()
        return projects
    }

    fun deleteProject(context: Context, uri: String) {
        val project = getProject(context, uri)
        val db = getHelper(context).writableDatabase
        db.delete(DatabaseHelper.TABLE_PROJECTS, "${DatabaseHelper.COLUMN_URI} = ?", arrayOf(uri))
        db.delete(DatabaseHelper.TABLE_AUDIO_LINES, "${DatabaseHelper.COLUMN_URI} = ?", arrayOf(uri))
        project?.audioPath?.let { path ->
            try { File(path).deleteRecursively() } catch (_: Exception) {}
        }
    }

    fun setBookmark(context: Context, uri: String, line: Int) {
        val db = getHelper(context).writableDatabase
        val values = ContentValues().apply {
            put(DatabaseHelper.COLUMN_BOOKMARK_LINE, line)
        }
        db.update(DatabaseHelper.TABLE_PROJECTS, values, "${DatabaseHelper.COLUMN_URI} = ?", arrayOf(uri))
    }

    fun getBookmark(context: Context, uri: String): Bookmark? {
        val db = getHelper(context).readableDatabase
        val cursor = db.query(
            DatabaseHelper.TABLE_PROJECTS,
            arrayOf(DatabaseHelper.COLUMN_BOOKMARK_LINE),
            "${DatabaseHelper.COLUMN_URI} = ?",
            arrayOf(uri),
            null,
            null,
            null
        )

        var bookmark: Bookmark? = null
        if (cursor.moveToFirst()) {
            val bookmarkLine = cursor.getInt(cursor.getColumnIndexOrThrow(DatabaseHelper.COLUMN_BOOKMARK_LINE))
            if (bookmarkLine != -1) {
                bookmark = Bookmark(bookmarkLine)
            }
        }

        cursor.close()
        return bookmark
    }

    fun clearBookmark(context: Context, uri: String) {
        val db = getHelper(context).writableDatabase
        val values = ContentValues().apply {
            put(DatabaseHelper.COLUMN_BOOKMARK_LINE, -1)
        }
        db.update(DatabaseHelper.TABLE_PROJECTS, values, "${DatabaseHelper.COLUMN_URI} = ?", arrayOf(uri))
    }

    fun setAudioLine(context: Context, uri: String, index: Int, path: String) {
        val db = getHelper(context).writableDatabase
        val values = ContentValues().apply {
            put(DatabaseHelper.COLUMN_URI, uri)
            put(DatabaseHelper.COLUMN_LINE_INDEX, index)
            put(DatabaseHelper.COLUMN_FILE_PATH, path)
        }
        db.replace(DatabaseHelper.TABLE_AUDIO_LINES, null, values)
    }

    fun getAudioLine(context: Context, uri: String, index: Int): String? {
        val db = getHelper(context).readableDatabase
        val cursor = db.query(
            DatabaseHelper.TABLE_AUDIO_LINES,
            arrayOf(DatabaseHelper.COLUMN_FILE_PATH),
            "${DatabaseHelper.COLUMN_URI} = ? AND ${DatabaseHelper.COLUMN_LINE_INDEX} = ?",
            arrayOf(uri, index.toString()),
            null,
            null,
            null
        )
        var path: String? = null
        if (cursor.moveToFirst()) {
            path = cursor.getString(cursor.getColumnIndexOrThrow(DatabaseHelper.COLUMN_FILE_PATH))
        }
        cursor.close()
        return path
    }

    fun clearAudioLines(context: Context, uri: String) {
        val db = getHelper(context).writableDatabase
        db.delete(DatabaseHelper.TABLE_AUDIO_LINES, "${DatabaseHelper.COLUMN_URI} = ?", arrayOf(uri))
    }

    fun setSetting(context: Context, key: String, value: String) {
        val db = getHelper(context).writableDatabase
        val values = ContentValues().apply {
            put(DatabaseHelper.COLUMN_KEY, key)
            put(DatabaseHelper.COLUMN_VALUE, value)
        }
        db.replace(DatabaseHelper.TABLE_SETTINGS, null, values)
    }

    fun getSetting(context: Context, key: String): String? {
        val db = getHelper(context).readableDatabase
        val cursor = db.query(
            DatabaseHelper.TABLE_SETTINGS,
            arrayOf(DatabaseHelper.COLUMN_VALUE),
            "${DatabaseHelper.COLUMN_KEY} = ?",
            arrayOf(key),
            null,
            null,
            null
        )

        var value: String? = null
        if (cursor.moveToFirst()) {
            value = cursor.getString(cursor.getColumnIndexOrThrow(DatabaseHelper.COLUMN_VALUE))
        }

        cursor.close()
        return value
    }
}
