package com.kanso.podcast.data

import android.util.Xml
import com.kanso.podcast.model.FeedEpisode
import com.kanso.podcast.model.PodcastFeed
import com.kanso.podcast.model.PodcastSearchResult
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream

/** RSS/Atom episode parser, separated from networking so it can be unit tested. */
object FeedParser {
    const val IMPORTED_AUTHOR = "Importado por OPML"
    private val TRANSCRIPT_PREFERENCE = listOf("vtt", "srt", "json", "html", "plain")

    fun parse(stream: InputStream, podcast: PodcastSearchResult): PodcastFeed {
        val parser = Xml.newPullParser().apply { setInput(stream, null) }
        val episodes = mutableListOf<FeedEpisode>()
        var inItem = false
        var title = ""; var description = ""; var audio = ""; var guid = ""
        var date = ""; var duration = ""; var image: String? = null
        var chapters: String? = null; var transcript: String? = null; var transcriptType: String? = null; var transcriptRank = 99
        var channelTitle = ""; var channelAuthor = ""; var channelImage: String? = null; var channelCategory = ""
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT && episodes.size < 100) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    // The pull parser is namespace-unaware, so names arrive as "itunes:duration".
                    // Some parsers are namespace-aware and report the prefix separately; handle both.
                    val raw = parser.name.lowercase()
                    val prefix = parser.prefix?.lowercase() ?: raw.substringBefore(':', "")
                    val name = raw.substringAfter(':')
                    if (prefix.isEmpty() && (name == "item" || name == "entry")) { inItem = true; title = ""; description = ""; audio = ""; guid = ""; date = ""; duration = ""; image = null; chapters = null; transcript = null; transcriptType = null; transcriptRank = 99 }
                    else if (!inItem) when {
                        prefix.isEmpty() && name == "title" && channelTitle.isBlank() && parser.depth <= 3 -> channelTitle = parser.nextText().trim()
                        prefix == "itunes" && name == "author" && channelAuthor.isBlank() -> channelAuthor = parser.nextText().trim()
                        prefix == "itunes" && name == "category" && channelCategory.isBlank() && parser.depth == 3 -> channelCategory = parser.getAttributeValue(null, "text")?.trim().orEmpty()
                        prefix == "itunes" && name == "image" && channelImage == null -> channelImage = parser.getAttributeValue(null, "href")?.takeIf { it.isNotBlank() }
                        prefix.isEmpty() && name == "url" && channelImage == null && parser.depth == 4 -> channelImage = parser.nextText().trim().takeIf { it.isNotBlank() }
                    }
                    else if (inItem && prefix == "podcast") when (name) {
                        "chapters" -> chapters = parser.getAttributeValue(null, "url")
                        "transcript" -> {
                            val type = parser.getAttributeValue(null, "type").orEmpty().lowercase()
                            val rank = TRANSCRIPT_PREFERENCE.indexOfFirst { type.contains(it) }.let { if (it < 0) 50 else it }
                            if (rank < transcriptRank) { transcriptRank = rank; transcript = parser.getAttributeValue(null, "url"); transcriptType = type }
                        }
                    }
                    else if (inItem && (prefix.isEmpty() || prefix == "itunes" || prefix == "content")) when (name) {
                        "title" -> if (prefix.isEmpty() || title.isBlank()) title = parser.nextText().trim()
                        "description", "summary" -> if (description.isBlank()) description = clean(parser.nextText())
                        "guid", "id" -> guid = parser.nextText().trim()
                        "pubdate", "published", "updated" -> if (date.isBlank()) date = parser.nextText().trim()
                        "duration" -> duration = parser.nextText().trim()
                        "enclosure" -> audio = parser.getAttributeValue(null, "url") ?: audio
                        "link" -> if (audio.isBlank() && parser.getAttributeValue(null, "rel") == "enclosure") audio = parser.getAttributeValue(null, "href") ?: ""
                        "image" -> if (prefix == "itunes") image = parser.getAttributeValue(null, "href")
                    }
                }
                XmlPullParser.END_TAG -> if (inItem && parser.prefix.isNullOrEmpty() && (parser.name.equals("item", true) || parser.name.equals("entry", true))) {
                    if (title.isNotBlank() && audio.isNotBlank()) episodes += FeedEpisode(
                        id = guid.ifBlank { audio }, title = title, description = description,
                        audioUrl = audio, published = friendlyDate(date), publishedAt = parseDate(date), duration = duration.ifBlank { "—" }, artworkUrl = image ?: podcast.artworkUrl,
                        chaptersUrl = chapters, transcriptUrl = transcript, transcriptType = transcriptType
                    )
                    inItem = false
                }
            }
            event = parser.next()
        }
        val placeholder = podcast.author.isBlank() || podcast.author == IMPORTED_AUTHOR
        val enriched = podcast.copy(
            title = if ((podcast.title.isBlank() || podcast.title == podcast.feedUrl) && channelTitle.isNotBlank()) channelTitle else podcast.title,
            author = if (placeholder && channelAuthor.isNotBlank()) channelAuthor else podcast.author,
            artworkUrl = podcast.artworkUrl ?: channelImage,
            genre = if ((podcast.genre.isBlank() || podcast.genre == "Podcast") && channelCategory.isNotBlank()) channelCategory else podcast.genre,
        )
        return PodcastFeed(enriched, episodes.map { if (it.artworkUrl == null && enriched.artworkUrl != null) it.copy(artworkUrl = enriched.artworkUrl) else it })
    }

    internal fun clean(text: String) = text.replace(Regex("<[^>]+>"), " ").replace(Regex("\\s+"), " ").trim().take(500)
    internal fun parseDate(value: String): Long {
        if (value.isBlank()) return 0L
        val patterns = listOf("EEE, dd MMM yyyy HH:mm:ss Z", "EEE, d MMM yyyy HH:mm:ss Z", "EEE, dd MMM yyyy HH:mm:ss zzz", "EEE, d MMM yyyy HH:mm zzz", "dd MMM yyyy HH:mm:ss Z", "yyyy-MM-dd'T'HH:mm:ssXXX", "yyyy-MM-dd'T'HH:mm:ss.SSSXXX")
        for (pattern in patterns) runCatching { return java.text.SimpleDateFormat(pattern, java.util.Locale.US).parse(value.trim())!!.time }
        return 0L
    }
    /** Short, localized date for display. */
    private fun friendlyDate(value: String): String {
        val millis = parseDate(value)
        if (millis > 0) return java.text.SimpleDateFormat("d MMM yyyy", java.util.Locale("es", "MX")).format(java.util.Date(millis))
        return legacyFriendlyDate(value)
    }
    private fun legacyFriendlyDate(value: String) = value.substringBefore(" +").substringBefore(" GMT").take(22).ifBlank { "Fecha desconocida" }
}
