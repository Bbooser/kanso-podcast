package com.kanso.podcast.data

import android.util.Xml
import com.kanso.podcast.model.PodcastSearchResult
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream
import java.net.URI

/** One subscription read from or written to OPML. [folder] is the enclosing outline (Pocket Casts folders, AntennaPod tags...). */
data class OpmlEntry(val feedUrl: String, val title: String, val folder: String? = null)

/** Result of reading an OPML file: unique valid entries plus counts of what was dropped. */
data class OpmlDocument(val entries: List<OpmlEntry>, val duplicates: Int, val invalid: Int)

/**
 * OPML import/export tolerant of the files written by Pocket Casts, AntennaPod, Podcast Addict, Apple Podcasts,
 * Overcast and Spotify converters: nested outlines (folders), xmlUrl spelling variants, feed:// / itpc:// / pcast://
 * schemes, unescaped ampersands, BOMs and duplicates (http vs https, www, trailing slash).
 */
object Opml {
    private val FEED_ATTRIBUTES = listOf("xmlurl", "url", "feedurl", "rss")
    private val WRAPPERS = setOf("feeds", "subscriptions", "podcasts", "suscripciones", "pocket casts feeds")
    private val BARE_AMPERSAND = Regex("&(?!(amp|lt|gt|quot|apos|#\\d+|#x[0-9a-fA-F]+);)")

    fun read(stream: InputStream): OpmlDocument {
        val text = stream.readBytes().toString(Charsets.UTF_8).removePrefix("\uFEFF").trimStart().let { BARE_AMPERSAND.replace(it, "&amp;") }
        val parser = Xml.newPullParser().apply { setInput(text.reader()) }
        val entries = LinkedHashMap<String, OpmlEntry>()
        var duplicates = 0; var invalid = 0; var sawOpml = false
        // One slot per open <outline>: the folder label it opens, or null for feeds/untitled groups.
        val stack = ArrayDeque<String?>()
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                val tag = parser.name.lowercase()
                if (tag == "opml") sawOpml = true
                if (tag == "outline") {
                    val attrs = (0 until parser.attributeCount).associate { parser.getAttributeName(it).lowercase().substringAfter(':') to parser.getAttributeValue(it).orEmpty().trim() }
                    val type = attrs["type"].orEmpty().lowercase()
                    val rawFeed = FEED_ATTRIBUTES.firstNotNullOfOrNull { key -> attrs[key]?.takeIf { it.isNotBlank() && (key != "url" || type in setOf("rss", "")) } }
                    val label = (attrs["title"]?.takeIf { it.isNotBlank() } ?: attrs["text"]?.takeIf { it.isNotBlank() })
                    if (rawFeed != null) {
                        val url = normalizeUrl(rawFeed)
                        if (url == null) invalid++
                        else {
                            val key = dedupeKey(url)
                            if (key in entries) duplicates++
                            else entries[key] = OpmlEntry(url, label ?: url, stack.lastOrNull { it != null })
                        }
                        stack.addLast(null)
                    } else if (type == "rss") { invalid++; stack.addLast(null) }
                    // Pocket Casts wraps everything in <outline text="feeds">: a top-level generic wrapper is not a folder.
                    else stack.addLast(label?.takeUnless { stack.isEmpty() && it.lowercase() in WRAPPERS })
                }
            } else if (event == XmlPullParser.END_TAG && parser.name.equals("outline", true)) stack.removeLastOrNull()
            event = parser.next()
        }
        if (!sawOpml) throw IllegalArgumentException("El archivo no es OPML")
        return OpmlDocument(entries.values.toList(), duplicates, invalid)
    }

    /** Backwards-compatible flat parse. */
    fun parse(stream: InputStream): List<PodcastSearchResult> = read(stream).entries.map { PodcastSearchResult(it.feedUrl.hashCode().toString(), it.title, FeedParser.IMPORTED_AUTHOR, it.feedUrl) }

    /** Converts podcast-app schemes to http(s) and rejects anything that is not a web URL. */
    fun normalizeUrl(raw: String): String? {
        var url = raw.trim().replace(" ", "%20")
        val lower = url.lowercase()
        url = when {
            lower.startsWith("feed:http") -> url.substring(5)
            lower.startsWith("feed://") || lower.startsWith("itpc://") || lower.startsWith("pcast://") || lower.startsWith("podcast://") -> "https://" + url.substringAfter("://")
            lower.startsWith("//") -> "https:$url"
            !lower.contains("://") && lower.contains('.') && !lower.startsWith("/") -> "https://$url"
            else -> url
        }
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        if (uri.scheme?.lowercase() !in setOf("http", "https") || uri.host.isNullOrBlank()) return null
        return url
    }

    /** Same feed regardless of http/https, "www.", host case or trailing slash. */
    fun dedupeKey(url: String): String {
        val uri = runCatching { URI(url) }.getOrNull() ?: return url.lowercase()
        val host = uri.host.orEmpty().lowercase().removePrefix("www.")
        val port = if (uri.port in setOf(-1, 80, 443)) "" else ":${uri.port}"
        val path = uri.rawPath.orEmpty().trimEnd('/')
        val query = uri.rawQuery?.let { "?$it" }.orEmpty()
        return host + port + path + query
    }

    fun create(items: List<PodcastSearchResult>): String = export(items.map { OpmlEntry(it.feedUrl, it.title) })

    /** OPML 2.0 with one nested outline per folder, the layout Pocket Casts and AntennaPod read back. */
    fun export(entries: List<OpmlEntry>, now: java.util.Date = java.util.Date()): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<opml version=\"2.0\">\n  <head>\n    <title>Kanso Podcast</title>\n")
        append("    <dateCreated>").append(java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss Z", java.util.Locale.US).format(now)).append("</dateCreated>\n  </head>\n  <body>\n")
        fun feed(e: OpmlEntry, indent: String) { append(indent).append("<outline type=\"rss\" text=\"").append(escape(e.title)).append("\" title=\"").append(escape(e.title)).append("\" xmlUrl=\"").append(escape(e.feedUrl)).append("\"/>\n") }
        entries.filter { it.folder.isNullOrBlank() }.forEach { feed(it, "    ") }
        entries.filter { !it.folder.isNullOrBlank() }.groupBy { it.folder!!.trim().lowercase() }.toSortedMap().values.forEach { items ->
            val folder = items.first().folder!!.trim()
            append("    <outline text=\"").append(escape(folder)).append("\" title=\"").append(escape(folder)).append("\">\n")
            items.forEach { feed(it, "      ") }
            append("    </outline>\n")
        }
        append("  </body>\n</opml>\n")
    }

    private fun escape(value: String) = value.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;")
}
