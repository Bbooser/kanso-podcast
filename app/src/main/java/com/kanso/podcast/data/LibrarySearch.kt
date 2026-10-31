package com.kanso.podcast.data

object LibrarySearch {
    /** Turns free text into an FTS4 prefix query ("cafe tec" -> "cafe* tec*"); null when there is nothing to search. */
    fun ftsQuery(text: String): String? {
        val tokens = text.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotBlank() }.take(8)
        return tokens.takeIf { it.isNotEmpty() }?.joinToString(" ") { "$it*" }
    }
}

/** A timestamp found in show notes: [start] until [end] (exclusive) in the text, pointing at [ms]. */
data class NoteTimestamp(val start: Int, val end: Int, val ms: Long)

object ShowNoteTimestamps {
    private val pattern = Regex("(?<![\\d:.])(?:(\\d{1,2}):)?(\\d{1,2}):(\\d{2})(?![\\d:])(?!\\s*(?:[aApP]\\.?\\s?[mM]|hrs?\\b|h\\b))")

    /** Finds 12:30 and 1:02:03 style times; ignores clock times like "10:30 pm" and anything past [maxMs] when known. */
    fun find(text: String, maxMs: Long? = null): List<NoteTimestamp> = pattern.findAll(text).mapNotNull { m ->
        val h = m.groupValues[1].toLongOrNull() ?: 0L
        val min = m.groupValues[2].toLong(); val sec = m.groupValues[3].toLong()
        if (sec >= 60 || (m.groupValues[1].isNotEmpty() && min >= 60)) return@mapNotNull null
        val ms = ((h * 60 + min) * 60 + sec) * 1000
        if (maxMs != null && maxMs > 0 && ms > maxMs) null else NoteTimestamp(m.range.first, m.range.last + 1, ms)
    }.toList()
}
