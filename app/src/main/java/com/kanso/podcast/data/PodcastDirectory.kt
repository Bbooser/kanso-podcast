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
    private fun loadEpisodesFromNetwork(podcast: PodcastSearchResult): PodcastFeed = open(podcast.feedUrl).inputStream.use { FeedParser.parse(it, podcast) }

    /** Top podcasts from Apple's charts (optionally for one genre), resolved to RSS feeds via the lookup API. */
    suspend fun trending(genreId: Int? = null, country: String = "mx", limit: Int = 30): List<PodcastSearchResult> = withContext(Dispatchers.IO) {
        val genre = genreId?.let { "/genre=$it" }.orEmpty()
        val entries = JSONObject(get("https://itunes.apple.com/$country/rss/toppodcasts/limit=$limit$genre/json")).getJSONObject("feed").optJSONArray("entry") ?: org.json.JSONArray()
        val ids = (0 until entries.length()).mapNotNull { i -> entries.getJSONObject(i).optJSONObject("id")?.optJSONObject("attributes")?.optString("im:id")?.takeIf { it.isNotBlank() } }
        if (ids.isEmpty()) return@withContext emptyList()
        val results = JSONObject(get("https://itunes.apple.com/lookup?country=$country&id=${ids.joinToString(",")}")).getJSONArray("results")
        val byId = (0 until results.length()).map { results.getJSONObject(it) }.filter { it.optString("feedUrl").isNotBlank() }.associateBy { it.optLong("collectionId").toString() }
        ids.mapNotNull { id -> byId[id]?.let(::fromApple) }
    }

    private fun fromApple(item: JSONObject) = PodcastSearchResult(
        id = item.optLong("collectionId").toString(),
        title = item.optString("collectionName", "Podcast"),
        author = item.optString("artistName", "Autor desconocido"),
        feedUrl = item.optString("feedUrl"),
        artworkUrl = item.optString("artworkUrl600").ifBlank { item.optString("artworkUrl100") },
        genre = item.optString("primaryGenreName", "Podcast")
    )

    /** Fetches a small text/JSON resource (chapters, transcripts). */
    suspend fun fetchText(url: String): String = withContext(Dispatchers.IO) { get(url) }

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
                    if (insideChannel && author.isBlank() && (name == "itunes:author" || name == "author")) author = parser.nextText().trim()
                    if (insideChannel && parser.name.equals("itunes:image", true)) artwork = parser.getAttributeValue(null, "href")
                    if (insideChannel && name == "image" && artwork == null) artwork = readImage(parser)
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
    private fun open(url: String) = (URI(url).toURL().openConnection() as HttpURLConnection).apply {
        connectTimeout = 12_000; readTimeout = 15_000; instanceFollowRedirects = true
        setRequestProperty("User-Agent", "KansoPodcast/0.4.0 (Android)")
        if (responseCode !in 200..299) throw IllegalStateException("El servidor respondió $responseCode")
    }
}
