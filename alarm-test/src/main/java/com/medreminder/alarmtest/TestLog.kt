package com.medreminder.alarmtest

import android.content.Context
import com.medreminder.alarm.AlarmMode
import org.json.JSONArray
import org.json.JSONObject

/**
 * Every alarm the test scheduled and what happened to it, kept on the phone so the
 * overnight result survives the app being killed or the phone rebooting.
 */
class TestLog(context: Context) {
    private val prefs = context.getSharedPreferences("alarm_test_log", Context.MODE_PRIVATE)

    data class Entry(
        val key: String,
        /** The first alarm of a reminder; repeats and snoozes share it. */
        val base: String,
        val scheduledAt: Long,
        val attempt: Int,
        val requestedMode: AlarmMode,
        val usedMode: AlarmMode,
        val firedAt: Long? = null,
        val screenShownAt: Long? = null,
        val cancelled: Boolean = false,
        val isSnooze: Boolean = false,
    )

    @Synchronized
    fun entries(): List<Entry> {
        val array = JSONArray(prefs.getString(KEY_ENTRIES, "[]"))
        return (0 until array.length()).map { fromJson(array.getJSONObject(it)) }
    }

    fun entry(key: String): Entry? = entries().firstOrNull { it.key == key }

    @Synchronized
    fun put(entry: Entry) {
        val list = entries().filterNot { it.key == entry.key } + entry
        save(list.sortedBy { it.scheduledAt }.takeLast(MAX_ENTRIES))
    }

    @Synchronized
    fun update(key: String, change: (Entry) -> Entry) {
        save(entries().map { if (it.key == key) change(it) else it })
    }

    fun takenAt(base: String): Long? = prefs.getLong("taken:$base", 0L).takeIf { it > 0 }

    fun setTaken(base: String, at: Long) = prefs.edit().putLong("taken:$base", at).apply()

    fun snoozeCount(base: String): Int = prefs.getInt("snoozes:$base", 0)

    fun addSnooze(base: String) = prefs.edit().putInt("snoozes:$base", snoozeCount(base) + 1).apply()

    fun events(): List<String> {
        val array = JSONArray(prefs.getString(KEY_EVENTS, "[]"))
        return (0 until array.length()).map { array.getString(it) }
    }

    fun addEvent(text: String) {
        val list = (events() + text).takeLast(MAX_EVENTS)
        prefs.edit().putString(KEY_EVENTS, JSONArray(list).toString()).apply()
    }

    fun clear() = prefs.edit().clear().apply()

    private fun save(list: List<Entry>) {
        val array = JSONArray()
        list.forEach { array.put(toJson(it)) }
        prefs.edit().putString(KEY_ENTRIES, array.toString()).apply()
    }

    private fun toJson(e: Entry) = JSONObject()
        .put("key", e.key)
        .put("base", e.base)
        .put("scheduledAt", e.scheduledAt)
        .put("attempt", e.attempt)
        .put("requestedMode", e.requestedMode.name)
        .put("usedMode", e.usedMode.name)
        .put("firedAt", e.firedAt ?: 0L)
        .put("screenShownAt", e.screenShownAt ?: 0L)
        .put("cancelled", e.cancelled)
        .put("isSnooze", e.isSnooze)

    private fun fromJson(o: JSONObject) = Entry(
        key = o.getString("key"),
        base = o.getString("base"),
        scheduledAt = o.getLong("scheduledAt"),
        attempt = o.getInt("attempt"),
        requestedMode = AlarmMode.valueOf(o.getString("requestedMode")),
        usedMode = AlarmMode.valueOf(o.getString("usedMode")),
        firedAt = o.optLong("firedAt").takeIf { it > 0 },
        screenShownAt = o.optLong("screenShownAt").takeIf { it > 0 },
        cancelled = o.optBoolean("cancelled"),
        isSnooze = o.optBoolean("isSnooze"),
    )

    private companion object {
        const val KEY_ENTRIES = "entries"
        const val KEY_EVENTS = "events"
        const val MAX_ENTRIES = 500
        const val MAX_EVENTS = 100
    }
}

/** What the tester chose on the main screen. */
class TestSettings(context: Context) {
    private val prefs = context.getSharedPreferences("alarm_test_settings", Context.MODE_PRIVATE)

    var mode: AlarmMode
        get() = AlarmMode.valueOf(prefs.getString("mode", AlarmMode.ALARM_CLOCK.name)!!)
        set(value) = prefs.edit().putString("mode", value.name).apply()

    /** Ring again 5 and 10 minutes later when nobody taps, as the real app will. */
    var repeatIfNotTapped: Boolean
        get() = prefs.getBoolean("repeat", true)
        set(value) = prefs.edit().putBoolean("repeat", value).apply()

    var hindi: Boolean
        get() = prefs.getBoolean("hindi", true)
        set(value) = prefs.edit().putBoolean("hindi", value).apply()
}
