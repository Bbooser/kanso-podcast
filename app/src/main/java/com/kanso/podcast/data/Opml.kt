package com.kanso.podcast.data

import android.util.Xml
import com.kanso.podcast.model.PodcastSearchResult
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream

object Opml {
    fun parse(stream: InputStream): List<PodcastSearchResult> {
        val parser = Xml.newPullParser().apply { setInput(stream, null) }
        val result = mutableListOf<PodcastSearchResult>()
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && parser.name.equals("outline", true)) {
                val feed = parser.getAttributeValue(null, "xmlUrl") ?: parser.getAttributeValue(null, "xmlurl")
                if (!feed.isNullOrBlank()) {
                    val title = parser.getAttributeValue(null, "title") ?: parser.getAttributeValue(null, "text") ?: feed
                    result += PodcastSearchResult(feed.hashCode().toString(), title, "Importado por OPML", feed)
                }
            }
            event = parser.next()
        }
        return result.distinctBy { it.feedUrl }
    }

    fun create(items: List<PodcastSearchResult>): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?><opml version=\"2.0\"><head><title>Kanso Podcast</title></head><body>")
        items.forEach { append("<outline type=\"rss\" text=\"").append(escape(it.title)).append("\" title=\"").append(escape(it.title)).append("\" xmlUrl=\"").append(escape(it.feedUrl)).append("\"/>") }
        append("</body></opml>")
    }

    private fun escape(value: String) = value.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;")
}
