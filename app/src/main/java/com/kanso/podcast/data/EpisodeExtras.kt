package com.kanso.podcast.data

import org.json.JSONObject

data class Chapter(val startMs: Long, val title: String, val imageUrl: String?, val url: String?)
data class TranscriptCue(val startMs: Long, val endMs: Long, val speaker: String?, val text: String)

/** Parsers for Podcasting 2.0 chapters (JSON) and transcripts (VTT, SRT, JSON, HTML, plain text). */
object EpisodeExtras {
    fun parseChapters(json: String): List<Chapter> {
        val array = JSONObject(json).optJSONArray("chapters") ?: return emptyList()
        return (0 until array.length()).mapNotNull { i ->
            val o = array.optJSONObject(i) ?: return@mapNotNull null
            if (o.has("toc") && !o.optBoolean("toc", true)) return@mapNotNull null
            Chapter((o.optDouble("startTime", 0.0) * 1000).toLong(), o.optString("title").ifBlank { "Capítulo ${i + 1}" }, o.optString("img").ifBlank { null }, o.optString("url").ifBlank { null })
        }.sortedBy { it.startMs }
    }

    fun parseTranscript(body: String, type: String?): List<TranscriptCue> {
        val t = type.orEmpty()
        return when {
            t.contains("json") || body.trimStart().startsWith("{") -> parseJson(body)
            t.contains("vtt") || t.contains("srt") || body.contains("-->") -> parseTimed(body)
            else -> plain(if (t.contains("html") || body.contains("<p", true)) body.replace(Regex("(?i)</p>|<br\\s*/?>"), "\n").replace(Regex("<[^>]+>"), "") else body)
        }
    }

    private fun plain(text: String) = text.split(Regex("\n\\s*\n|\n")).map { it.trim() }.filter { it.isNotEmpty() }.map { TranscriptCue(-1, -1, null, decode(it)) }

    private fun parseJson(body: String): List<TranscriptCue> {
        val segments = JSONObject(body).optJSONArray("segments") ?: return emptyList()
        val raw = (0 until segments.length()).mapNotNull { segments.optJSONObject(it) }.map {
            TranscriptCue((it.optDouble("startTime") * 1000).toLong(), (it.optDouble("endTime") * 1000).toLong(), it.optString("speaker").ifBlank { null }, it.optString("body"))
        }
        // Word-level JSON transcripts: merge into sentence-sized cues.
        val merged = mutableListOf<TranscriptCue>()
        raw.forEach { cue ->
            val last = merged.lastOrNull()
            if (last != null && last.speaker == cue.speaker && last.text.length < 160 && !last.text.endsWith(".") && !last.text.endsWith("?")) merged[merged.lastIndex] = last.copy(endMs = cue.endMs, text = (last.text + " " + cue.text).trim())
            else merged += cue
        }
        return merged
    }

    private val timeLine = Regex("""((?:\d+:)?\d{1,2}:\d{2}[.,]\d{1,3})\s*-->\s*((?:\d+:)?\d{1,2}:\d{2}[.,]\d{1,3})""")

    private fun parseTimed(body: String): List<TranscriptCue> {
        val cues = mutableListOf<TranscriptCue>()
        body.replace("\r", "").split(Regex("\n\\s*\n")).forEach { block ->
            val lines = block.lines().filter { it.isNotBlank() }
            val index = lines.indexOfFirst { timeLine.containsMatchIn(it) }
            if (index < 0) return@forEach
            val m = timeLine.find(lines[index])!!
            var text = lines.drop(index + 1).joinToString(" ").trim()
            var speaker: String? = null
            Regex("""^<v\s+([^>]+)>""").find(text)?.let { speaker = it.groupValues[1].trim(); text = text.removePrefix(it.value) }
            text = decode(text.replace(Regex("<[^>]+>"), "")).trim()
            if (text.isNotEmpty()) cues += TranscriptCue(time(m.groupValues[1]), time(m.groupValues[2]), speaker, text)
        }
        return cues
    }

    private fun time(value: String): Long {
        val parts = value.replace(',', '.').split(':')
        val seconds = parts.last().toDouble()
        val minutes = parts.getOrNull(parts.size - 2)?.toLong() ?: 0
        val hours = if (parts.size == 3) parts[0].toLong() else 0
        return ((hours * 3600 + minutes * 60) * 1000) + (seconds * 1000).toLong()
    }

    private fun decode(s: String) = s.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'").replace("&nbsp;", " ")
}
