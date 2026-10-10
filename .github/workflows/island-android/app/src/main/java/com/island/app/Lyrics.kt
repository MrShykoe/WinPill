package com.island.app

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class LrcLine(val t: Double, val s: String)

// Слова песни с lrclib.net (то же, что и в версии для Windows).
object Lyrics {
    private val cache = HashMap<String, List<LrcLine>?>()

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    private fun stripJunk(t: String): String {
        var r = t.replace(Regex("""\s*[\(\[].*?[\)\]]"""), "")
        r = r.replace(Regex("""\s+(feat\.?|ft\.?)\s.*$""", RegexOption.IGNORE_CASE), "")
        return r.trim()
    }

    private fun cleanArtist(a: String): String =
        a.replace(" - Topic", "")
            .split(Regex(""",|&|\bfeat\.?\b|\bft\.?\b""", RegexOption.IGNORE_CASE))[0].trim()

    // Из «Исполнитель - Название (Official Video)» нужно уметь достать настоящую пару.
    private fun candidates(title: String, artist: String): List<Pair<String, String>> {
        val res = ArrayList<Pair<String, String>>()
        val a = cleanArtist(artist)
        val parts = title.split(" - ").filter { it.isNotEmpty() }
        if (parts.size >= 2) {
            if (a.isNotEmpty() && parts[0].contains(a, ignoreCase = true)) res.add(Pair(a, stripJunk(parts[1])))
            res.add(Pair(stripJunk(parts[0]), stripJunk(parts[1])))
            res.add(Pair(a, stripJunk(parts[0])))
        }
        res.add(Pair(a, stripJunk(title)))
        return res.filter { it.second.isNotEmpty() }.distinct().take(4)
    }

    private fun httpGet(url: String): String? {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 6000
        c.readTimeout = 6000
        c.setRequestProperty("User-Agent", "Island-Android/1.0 (personal island)")
        return try {
            if (c.responseCode in 200..299) c.inputStream.bufferedReader().use { it.readText() } else null
        } finally {
            c.disconnect()
        }
    }

    private fun syncedOf(o: JSONObject): String? {
        if (o.isNull("syncedLyrics")) return null
        val s = o.optString("syncedLyrics", "")
        return if (s.isNotBlank()) s else null
    }

    private fun lrclibGet(a: String, t: String, dur: Double): String? {
        var q = "artist_name=${enc(a)}&track_name=${enc(t)}"
        if (dur > 1) q += "&duration=${Math.round(dur)}"
        val body = httpGet("https://lrclib.net/api/get?$q") ?: return null
        return syncedOf(JSONObject(body))
    }

    private fun lrclibSearch(query: String, dur: Double): String? {
        val body = httpGet("https://lrclib.net/api/search?$query") ?: return null
        val arr = JSONArray(body)
        var best: String? = null
        var bestDiff = Double.MAX_VALUE
        for (i in 0 until arr.length()) {
            val el = arr.optJSONObject(i) ?: continue
            val syn = syncedOf(el) ?: continue
            val d = if (dur > 1) Math.abs(el.optDouble("duration", 0.0) - dur) else 0.0
            if (dur > 1 && d > 8) continue // другая версия трека
            if (d < bestDiff) {
                bestDiff = d
                best = syn
            }
        }
        return best
    }

    private fun parseLrc(lrc: String): List<LrcLine> {
        val res = ArrayList<LrcLine>()
        val stamp = Regex("""\[(\d+):(\d+(?:\.\d+)?)\]""")
        val credit = Regex("""^(作词|作曲|编曲|制作|Lyrics by|Composed by|Arranged by|Produced by)""", RegexOption.IGNORE_CASE)
        for (raw in lrc.split('\n')) {
            val ms = stamp.findAll(raw).toList()
            if (ms.isEmpty()) continue
            val text = stamp.replace(raw, "").trim()
            if (credit.containsMatchIn(text)) continue
            for (m in ms) {
                val t = m.groupValues[1].toInt() * 60 + m.groupValues[2].toDouble()
                res.add(LrcLine(t, text))
            }
        }
        res.sortBy { it.t }
        return res
    }

    // Блокирующий вызов: запускать только в фоновом потоке.
    fun get(title: String, artist: String, durSec: Double): List<LrcLine>? {
        val key = "$artist|$title"
        synchronized(cache) {
            if (cache.containsKey(key)) return cache[key]
        }
        var lrc: String? = null
        for ((a, t) in candidates(title, artist)) {
            try {
                lrc = lrclibGet(a, t, durSec)
            } catch (e: Exception) {
            }
            if (lrc != null) break
            try {
                lrc = lrclibSearch("track_name=${enc(t)}&artist_name=${enc(a)}", durSec)
                if (lrc == null && a.isNotEmpty()) lrc = lrclibSearch("q=${enc("$a $t")}", durSec)
            } catch (e: Exception) {
            }
            if (lrc != null) break
        }
        val lines = lrc?.let { parseLrc(it) }?.takeIf { it.size > 2 }
        synchronized(cache) { cache[key] = lines }
        return lines
    }
}
