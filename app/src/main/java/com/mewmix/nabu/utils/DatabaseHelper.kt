package com.mewmix.nabu.utils

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class DatabaseHelper(
    context: Context,
    databaseName: String? = DATABASE_NAME
) : SQLiteOpenHelper(context, databaseName, null, DATABASE_VERSION) {

    companion object {
        private const val DATABASE_NAME = "kokoro.db"
        const val DATABASE_VERSION = 7
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

        const val TABLE_VOICE_MIXES = "voice_mixes"
        const val COLUMN_POSITION = "position"

        const val TABLE_AUTOMATION_TRACE_SESSIONS = "automation_trace_sessions"
        const val TABLE_AUTOMATION_TRACE_STEPS = "automation_trace_steps"
        const val TABLE_AUTOMATION_TRACE_EVENTS = "automation_trace_events"

        fun createAutomationTraceTables(db: SQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS $TABLE_AUTOMATION_TRACE_SESSIONS (
                    session_id TEXT PRIMARY KEY,
                    schema_version INTEGER NOT NULL,
                    goal_digest TEXT NOT NULL,
                    checkpoint_id TEXT,
                    status TEXT NOT NULL,
                    verification_status TEXT NOT NULL,
                    termination_reason TEXT,
                    step_count INTEGER NOT NULL DEFAULT 0,
                    recovery_attempt_count INTEGER NOT NULL DEFAULT 0,
                    last_state_fingerprint TEXT,
                    last_package TEXT,
                    last_window TEXT,
                    screenshot_opt_in INTEGER NOT NULL DEFAULT 0,
                    created_at_ms INTEGER NOT NULL,
                    updated_at_ms INTEGER NOT NULL
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS $TABLE_AUTOMATION_TRACE_STEPS (
                    _id INTEGER PRIMARY KEY AUTOINCREMENT,
                    session_id TEXT NOT NULL,
                    step_sequence INTEGER NOT NULL,
                    checkpoint_id TEXT,
                    observation_id TEXT,
                    before_fingerprint TEXT,
                    after_fingerprint TEXT,
                    intended_action TEXT NOT NULL,
                    sanitized_target TEXT,
                    lease_validation_outcome TEXT NOT NULL,
                    execution_result TEXT NOT NULL,
                    transport_status TEXT NOT NULL,
                    postcondition_status TEXT,
                    verification_status TEXT NOT NULL,
                    evidence_category TEXT,
                    failure_classification TEXT,
                    observation_ms INTEGER NOT NULL DEFAULT 0,
                    model_decision_ms INTEGER NOT NULL DEFAULT 0,
                    execution_ms INTEGER NOT NULL DEFAULT 0,
                    verification_ms INTEGER NOT NULL DEFAULT 0,
                    total_latency_ms INTEGER NOT NULL DEFAULT 0,
                    recovery_attempts INTEGER NOT NULL DEFAULT 0,
                    recovery_summary TEXT,
                    termination_reason TEXT,
                    source_package TEXT,
                    result_package TEXT,
                    source_window TEXT,
                    result_window TEXT,
                    screenshot_ref TEXT,
                    recorded_at_ms INTEGER NOT NULL,
                    UNIQUE(session_id, step_sequence) ON CONFLICT REPLACE
                )
                """.trimIndent()
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS idx_trace_steps_session ON $TABLE_AUTOMATION_TRACE_STEPS(session_id, step_sequence)"
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS $TABLE_AUTOMATION_TRACE_EVENTS (
                    _id INTEGER PRIMARY KEY AUTOINCREMENT,
                    session_id TEXT NOT NULL,
                    event_sequence INTEGER NOT NULL,
                    elapsed_ms INTEGER NOT NULL,
                    event_name TEXT NOT NULL,
                    payload_json TEXT NOT NULL,
                    recorded_at_ms INTEGER NOT NULL,
                    UNIQUE(session_id, event_sequence) ON CONFLICT REPLACE
                )
                """.trimIndent()
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS idx_trace_events_session ON $TABLE_AUTOMATION_TRACE_EVENTS(session_id, event_sequence)"
            )
        }
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

        createVoiceMixesTable(db)
        createAutomationTraceTables(db)
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

        if (oldVersion < 6) {
            createVoiceMixesTable(db)
        }

        if (oldVersion < 7) {
            createAutomationTraceTables(db)
        }
    }

    private fun createVoiceMixesTable(db: SQLiteDatabase) {
        val createVoiceMixesTable = "CREATE TABLE IF NOT EXISTS $TABLE_VOICE_MIXES (" +
                "$COLUMN_ID INTEGER PRIMARY KEY AUTOINCREMENT," +
                "$COLUMN_NAME TEXT NOT NULL UNIQUE," +
                "$COLUMN_STYLES TEXT NOT NULL," +
                "$COLUMN_WEIGHTS TEXT NOT NULL," +
                "$COLUMN_MODE TEXT NOT NULL," +
                "$COLUMN_POSITION INTEGER NOT NULL DEFAULT 0)"
        db.execSQL(createVoiceMixesTable)
    }
}
