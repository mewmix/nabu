package com.mewmix.nabu.actions

import android.content.Context
import androidx.core.content.edit
import org.json.JSONArray

object MemoryStore {
    private const val PREFS_NAME = "nabu_memories"
    private const val KEY_MEMORIES = "memories_list"

    fun saveMemory(context: Context, fact: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val currentJson = prefs.getString(KEY_MEMORIES, "[]")
        val array = JSONArray(currentJson)
        array.put(fact)
        prefs.edit { putString(KEY_MEMORIES, array.toString()) }
    }

    fun retrieveMemories(context: Context): List<String> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val currentJson = prefs.getString(KEY_MEMORIES, "[]")
        val array = JSONArray(currentJson)
        val result = mutableListOf<String>()
        for (i in 0 until array.length()) {
            result.add(array.getString(i))
        }
        return result
    }
}