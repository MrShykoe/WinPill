package com.island.app

import android.content.Context
import org.json.JSONArray

object K {
    const val NOTIF = "notif"
    const val VOLUME = "volume"
    const val BRIGHT = "bright"
    const val PLAYBACK = "playback"
    const val EQ = "eq"
    const val TINT = "tint"
    const val LYRICS = "lyrics"
    const val KARAOKE = "karaoke"
    const val CLOCK = "clock"
    const val BATTERY = "battery"
    const val NETWORK = "network"
    const val BT = "bt"
    const val THEME = "theme"
    const val OFFSET = "offset"
}

object Prefs {
    private fun sp(c: Context) = c.getSharedPreferences("island", Context.MODE_PRIVATE)

    fun b(c: Context, k: String, d: Boolean = true): Boolean = sp(c).getBoolean(k, d)
    fun setB(c: Context, k: String, v: Boolean) = sp(c).edit().putBoolean(k, v).apply()
    fun i(c: Context, k: String, d: Int): Int = sp(c).getInt(k, d)
    fun setI(c: Context, k: String, v: Int) = sp(c).edit().putInt(k, v).apply()

    fun notes(c: Context): MutableList<String> {
        val res = ArrayList<String>()
        try {
            val arr = JSONArray(sp(c).getString("notes", "[]") ?: "[]")
            for (i in 0 until arr.length()) res.add(arr.getString(i))
        } catch (e: Exception) {
        }
        return res
    }

    fun saveNotes(c: Context, list: List<String>) {
        val arr = JSONArray()
        for (s in list) arr.put(s)
        sp(c).edit().putString("notes", arr.toString()).apply()
    }
}
