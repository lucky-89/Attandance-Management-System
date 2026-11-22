package com.finowise.hr.data

import android.content.Context

/** Who is signed in on this device. */
class Session(context: Context) {
    private val prefs = context.getSharedPreferences("session", Context.MODE_PRIVATE)
    var userId: String?
        get() = prefs.getString("userId", null)
        set(v) { prefs.edit().putString("userId", v).apply() }
    fun reminderShown(key: String): Long = prefs.getLong("nudge_$key", 0)
    fun setReminderShown(key: String, at: Long) { prefs.edit().putLong("nudge_$key", at).apply() }
}
