package com.mewmix.nabu.actions

data class ScheduledAction(
    val id: String,
    val title: String,
    val instruction: String,
    val triggerAtEpochMs: Long,
    val recurrence: String = RECURRENCE_NONE,
    val createdAtEpochMs: Long = System.currentTimeMillis()
) {
    companion object {
        const val RECURRENCE_NONE = "none"
        const val RECURRENCE_DAILY = "daily"
        const val RECURRENCE_WEEKLY = "weekly"
    }
}
