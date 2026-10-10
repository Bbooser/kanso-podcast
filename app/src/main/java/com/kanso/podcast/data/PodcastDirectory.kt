package com.kanso.podcast.data

import android.util.Xml
import android.content.Context
import com.kanso.podcast.model.PodcastSearchResult
import com.kanso.podcast.model.FeedEpisode
import com.kanso.podcast.model.PodcastFeed
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder

class PodcastDirectory {
    suspend fun search(input: String): List<PodcastSearchResult> = withContext(Dispatchers.IO) {
        val query = input.trim()
        if (query.startsWith("http://") || query.startsWith("https://")) listOf(readFeed(query))
        else searchApple(query)
    }

    /** Fetches and parses the feed from the network. Offline fallback is handled by [com.kanso.podcast.data.KansoRepository]. */
    suspend fun fetchEpisodes(podcast: PodcastSearchResult): PodcastFeed = withContext(Dispatchers.IO) { loadEpisodesFromNetwork(podcast) }
    private fun loadEpisodesFromNetwork(podcast: PodcastSearchResult): PodcastFeed {
        val connection = open(podcast.feedUrl)
        return connection.inputStream.use { stream ->
            val parser = Xml.newPullParser().apply { setInput(stream, null) }
            val episodes = mutableListOf<FeedEpisode>()
            var inItem = false
            var title = ""; var description = ""; var audio = ""; var guid = ""
            var date = ""; var duration = ""; var image: String? = null
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT && episodes.size < 100) {
                when (event) {
                    XmlPullParser.START_TAG -> {
                        val name = parser.name.lowercase()
                        if (name == "item" || name == "entry") { inItem = true; title = ""; description = ""; audio = ""; guid = ""; date = ""; duration = ""; image = null }
                        else if (inItem) when (name) {
                            "title" -> title = parser.nextText().trim()
                            "description", "summary" -> if (description.isBlank()) description = clean(parser.nextText())
                            "guid", "id" -> guid = parser.nextText().trim()
                            "pubdate", "published", "updated" -> if (date.isBlank()) date = parser.nextText().trim()
                            "duration" -> duration = parser.nextText().trim()
                            "enclosure" -> audio = parser.getAttributeValue(null, "url") ?: audio
                            "link" -> if (audio.isBlank() && parser.getAttributeValue(null, "rel") == "enclosure") audio = parser.getAttributeValue(null, "href") ?: ""
                            "image" -> if (parser.prefix == "itunes") image = parser.getAttributeValue(null, "href")
                        }
                    }
                    XmlPullParser.END_TAG -> if (inItem && (parser.name.equals("item", true) || parser.name.equals("entry", true))) {
                        if (title.isNotBlank() && audio.isNotBlank()) episodes += FeedEpisode(
                            id = guid.ifBlank { audio }, title = title, description = description,
                            audioUrl = audio, published = friendlyDate(date), publishedAt = parseDate(date), duration = duration.ifBlank { "—" }, artworkUrl = image ?: podcast.artworkUrl
                        )
                        inItem = false
                    }
                }
                event = parser.next()
            }
            PodcastFeed(podcast, episodes)
        }
    }

    private fun searchApple(query: String): List<PodcastSearchResult> {
        val encoded = URLEncoder.encode(query, Charsets.UTF_8.name())
        val body = get("https://itunes.apple.com/search?media=podcast&entity=podcast&limit=25&term=$encoded")
        val results = JSONObject(body).getJSONArray("results")
        return buildList {
            for (i in 0 until results.length()) {
                val item = results.getJSONObject(i)
                val feed = item.optString("feedUrl")
                if (feed.isNotBlank()) add(PodcastSearchResult(
                    id = item.optLong("collectionId", feed.hashCode().toLong()).toString(),
                    title = item.optString("collectionName", "Podcast"),
                    author = item.optString("artistName", "Autor desconocido"),
                    feedUrl = feed,
                    artworkUrl = item.optString("artworkUrl600").ifBlank { item.optString("artworkUrl100") },
                    genre = item.optString("primaryGenreName", "Podcast")
                ))
            }
        }
    }

    private fun readFeed(url: String): PodcastSearchResult {
        val connection = open(url)
        connection.inputStream.use { stream ->
            val parser = Xml.newPullParser().apply { setInput(stream, null) }
            var title = ""
            var author = ""
            var artwork: String? = null
            var insideChannel = false
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG) {
                    val name = parser.name.lowercase()
                    if (name == "channel") insideChannel = true
                    if (insideChannel && title.isBlank() && name == "title") title = parser.nextText().trim()
                    if (insideChannel && author.isBlank() && name == "author") author = parser.nextText().trim()
                    if (insideChannel && name == "image" && parser.prefix == "itunes") artwork = parser.getAttributeValue(null, "href")
                    if (insideChannel && name == "image" && parser.prefix != "itunes" && artwork == null) artwork = readImage(parser)
                }
                if (event == XmlPullParser.END_TAG && parser.name.equals("channel", true)) break
                event = parser.next()
            }
            if (title.isBlank()) throw IllegalArgumentException("El enlace no parece ser un RSS de podcast válido")
            return PodcastSearchResult(url.hashCode().toString(), title, author.ifBlank { URI(url).host }, url, artwork)
        }
    }

    private fun readImage(parser: XmlPullParser): String? {
        var event = parser.next()
        while (!(event == XmlPullParser.END_TAG && parser.name.equals("image", true))) {
            if (event == XmlPullParser.START_TAG && parser.name.equals("url", true)) return parser.nextText().trim()
            event = parser.next()
        }
        return null
    }

    private fun get(url: String): String = open(url).inputStream.bufferedReader().use { it.readText() }
    private fun clean(text: String) = text.replace(Regex("<[^>]+>"), " ").replace(Regex("\\s+"), " ").trim().take(500)
    private fun parseDate(value: String): Long {
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
    private fun open(url: String) = (URI(url).toURL().openConnection() as HttpURLConnection).apply {
        connectTimeout = 12_000; readTimeout = 15_000; instanceFollowRedirects = true
        setRequestProperty("User-Agent", "KansoPodcast/0.2.0 (Android)")
        if (responseCode !in 200..299) throw IllegalStateException("El servidor respondió $responseCode")
    }
}
