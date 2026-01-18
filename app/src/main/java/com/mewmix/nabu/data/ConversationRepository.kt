package com.mewmix.nabu.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import com.mewmix.nabu.utils.DatabaseHelper
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

object ConversationRepository {
    private val gson = Gson()
    private val messageListType =
        TypeToken.getParameterized(List::class.java, ConversationTurn::class.java).type

    fun createConversation(
        context: Context,
        title: String,
        modelId: String?,
        messages: List<ConversationTurn>,
    ): Conversation {
        val now = System.currentTimeMillis()
        val dbHelper = DatabaseHelper(context)
        val db = dbHelper.writableDatabase
        val values = ContentValues().apply {
            put(DatabaseHelper.COLUMN_TITLE, title)
            put(DatabaseHelper.COLUMN_MODEL_ID, modelId)
            put(DatabaseHelper.COLUMN_MESSAGES, gson.toJson(messages, messageListType))
            put(DatabaseHelper.COLUMN_CREATED_AT, now)
            put(DatabaseHelper.COLUMN_UPDATED_AT, now)
        }
        val id = db.insert(DatabaseHelper.TABLE_CONVERSATIONS, null, values)
        db.close()
        return Conversation(id, title, modelId, messages, now, now)
    }

    fun getConversation(context: Context, conversationId: Long): Conversation? {
        val dbHelper = DatabaseHelper(context)
        val db = dbHelper.readableDatabase
        val cursor = db.query(
            DatabaseHelper.TABLE_CONVERSATIONS,
            null,
            "${DatabaseHelper.COLUMN_ID} = ?",
            arrayOf(conversationId.toString()),
            null,
            null,
            null
        )
        val conversation = if (cursor.moveToFirst()) cursor.toConversation() else null
        cursor.close()
        db.close()
        return conversation
    }

    fun getConversationSummaries(context: Context): List<ConversationSummary> {
        val dbHelper = DatabaseHelper(context)
        val db = dbHelper.readableDatabase
        val cursor = db.query(
            DatabaseHelper.TABLE_CONVERSATIONS,
            arrayOf(
                DatabaseHelper.COLUMN_ID,
                DatabaseHelper.COLUMN_TITLE,
                DatabaseHelper.COLUMN_MODEL_ID,
                DatabaseHelper.COLUMN_UPDATED_AT
            ),
            null,
            null,
            null,
            null,
            "${DatabaseHelper.COLUMN_UPDATED_AT} DESC"
        )

        val summaries = mutableListOf<ConversationSummary>()
        while (cursor.moveToNext()) {
            val id = cursor.getLong(cursor.getColumnIndexOrThrow(DatabaseHelper.COLUMN_ID))
            val title = cursor.getString(cursor.getColumnIndexOrThrow(DatabaseHelper.COLUMN_TITLE))
            val modelId = cursor.getString(cursor.getColumnIndexOrThrow(DatabaseHelper.COLUMN_MODEL_ID))
            val updatedAt = cursor.getLong(cursor.getColumnIndexOrThrow(DatabaseHelper.COLUMN_UPDATED_AT))
            summaries.add(ConversationSummary(id, title, modelId, updatedAt))
        }
        cursor.close()
        db.close()
        return summaries
    }

    fun updateMessages(
        context: Context,
        conversationId: Long,
        messages: List<ConversationTurn>,
    ): Long {
        val now = System.currentTimeMillis()
        val dbHelper = DatabaseHelper(context)
        val db = dbHelper.writableDatabase
        val values = ContentValues().apply {
            put(DatabaseHelper.COLUMN_MESSAGES, gson.toJson(messages, messageListType))
            put(DatabaseHelper.COLUMN_UPDATED_AT, now)
        }
        db.update(
            DatabaseHelper.TABLE_CONVERSATIONS,
            values,
            "${DatabaseHelper.COLUMN_ID} = ?",
            arrayOf(conversationId.toString())
        )
        db.close()
        return now
    }

    fun updateModel(context: Context, conversationId: Long, modelId: String?): Long {
        val now = System.currentTimeMillis()
        val dbHelper = DatabaseHelper(context)
        val db = dbHelper.writableDatabase
        val values = ContentValues().apply {
            put(DatabaseHelper.COLUMN_MODEL_ID, modelId)
            put(DatabaseHelper.COLUMN_UPDATED_AT, now)
        }
        db.update(
            DatabaseHelper.TABLE_CONVERSATIONS,
            values,
            "${DatabaseHelper.COLUMN_ID} = ?",
            arrayOf(conversationId.toString())
        )
        db.close()
        return now
    }

    fun renameConversation(context: Context, conversationId: Long, title: String): Long {
        val now = System.currentTimeMillis()
        val dbHelper = DatabaseHelper(context)
        val db = dbHelper.writableDatabase
        val values = ContentValues().apply {
            put(DatabaseHelper.COLUMN_TITLE, title)
            put(DatabaseHelper.COLUMN_UPDATED_AT, now)
        }
        db.update(
            DatabaseHelper.TABLE_CONVERSATIONS,
            values,
            "${DatabaseHelper.COLUMN_ID} = ?",
            arrayOf(conversationId.toString())
        )
        db.close()
        return now
    }

    fun deleteConversation(context: Context, conversationId: Long) {
        val dbHelper = DatabaseHelper(context)
        val db = dbHelper.writableDatabase
        db.delete(
            DatabaseHelper.TABLE_CONVERSATIONS,
            "${DatabaseHelper.COLUMN_ID} = ?",
            arrayOf(conversationId.toString())
        )
        db.close()
    }

    private fun Cursor.toConversation(): Conversation {
        val id = getLong(getColumnIndexOrThrow(DatabaseHelper.COLUMN_ID))
        val title = getString(getColumnIndexOrThrow(DatabaseHelper.COLUMN_TITLE))
        val modelId = getString(getColumnIndexOrThrow(DatabaseHelper.COLUMN_MODEL_ID))
        val messagesJson = getString(getColumnIndexOrThrow(DatabaseHelper.COLUMN_MESSAGES))
        val createdAt = getLong(getColumnIndexOrThrow(DatabaseHelper.COLUMN_CREATED_AT))
        val updatedAt = getLong(getColumnIndexOrThrow(DatabaseHelper.COLUMN_UPDATED_AT))
        val messages = gson.fromJson<List<ConversationTurn>>(messagesJson, messageListType) ?: emptyList()
        return Conversation(id, title, modelId, messages, createdAt, updatedAt)
    }
}
