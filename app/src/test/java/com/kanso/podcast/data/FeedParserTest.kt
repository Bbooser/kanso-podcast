package com.kanso.podcast.data

import com.kanso.podcast.model.PodcastSearchResult
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FeedParserTest {
    private val imported = PodcastSearchResult("1", "https://ejemplo.mx/feed.xml", FeedParser.IMPORTED_AUTHOR, "https://ejemplo.mx/feed.xml")

    private val rss = """<?xml version="1.0" encoding="UTF-8"?>
<rss version="2.0" xmlns:itunes="http://www.itunes.com/dtds/podcast-1.0.dtd" xmlns:podcast="https://podcastindex.org/namespace/1.0">
  <channel>
    <title>Café Kanso</title>
    <itunes:author>Pablo</itunes:author>
    <itunes:image href="https://ejemplo.mx/portada.jpg"/>
    <item>
      <title>Episodio 2</title>
      <description><![CDATA[<p>Hola <b>mundo</b></p>]]></description>
      <guid>ep-2</guid>
      <pubDate>Tue, 27 Oct 2026 10:00:00 +0000</pubDate>
      <itunes:duration>1:02:03</itunes:duration>
      <enclosure url="https://ejemplo.mx/2.mp3" type="audio/mpeg" length="1"/>
      <podcast:transcript url="https://ejemplo.mx/2.html" type="text/html"/>
      <podcast:transcript url="https://ejemplo.mx/2.vtt" type="text/vtt"/>
      <podcast:chapters url="https://ejemplo.mx/2.json" type="application/json+chapters"/>
    </item>
    <item>
      <title>Sin audio</title>
      <guid>ep-x</guid>
    </item>
    <item>
      <title>Episodio 1</title>
      <enclosure url="https://ejemplo.mx/1.m4a" type="audio/mp4"/>
      <itunes:image href="https://ejemplo.mx/1.jpg"/>
    </item>
  </channel>
</rss>"""

    @Test fun parsesItemsAndSkipsThoseWithoutAudio() {
        val feed = FeedParser.parse(rss.byteInputStream(), imported)
        assertEquals(listOf("Episodio 2", "Episodio 1"), feed.episodes.map { it.title })
        val e = feed.episodes[0]
        assertEquals("ep-2", e.id)
        assertEquals("https://ejemplo.mx/2.mp3", e.audioUrl)
        assertEquals("1:02:03", e.duration)
        assertEquals("Hola mundo", e.description)
        assertTrue(e.publishedAt > 0)
        assertEquals("https://ejemplo.mx/2.vtt", e.transcriptUrl)
        assertEquals("https://ejemplo.mx/2.json", e.chaptersUrl)
    }

    @Test fun missingGuidFallsBackToAudioUrlAndItemArtwork() {
        val e = FeedParser.parse(rss.byteInputStream(), imported).episodes[1]
        assertEquals("https://ejemplo.mx/1.m4a", e.id)
        assertEquals("https://ejemplo.mx/1.jpg", e.artworkUrl)
        assertEquals("—", e.duration)
    }

    @Test fun fillsPodcastMetadataForImportedFeeds() {
        val feed = FeedParser.parse(rss.byteInputStream(), imported)
        assertEquals("Café Kanso", feed.podcast.title)
        assertEquals("Pablo", feed.podcast.author)
        assertEquals("https://ejemplo.mx/portada.jpg", feed.podcast.artworkUrl)
        assertEquals("https://ejemplo.mx/portada.jpg", feed.episodes[0].artworkUrl)
    }

    @Test fun keepsExistingMetadata() {
        val known = PodcastSearchResult("1", "Mi título", "Autor real", "https://ejemplo.mx/feed.xml", "https://otro/img.png")
        val feed = FeedParser.parse(rss.byteInputStream(), known)
        assertEquals("Mi título", feed.podcast.title); assertEquals("Autor real", feed.podcast.author); assertEquals("https://otro/img.png", feed.podcast.artworkUrl)
    }

    @Test fun parsesAtomEnclosureLinks() {
        val atom = """<feed xmlns="http://www.w3.org/2005/Atom"><title>Atom</title>
            <entry><title>Uno</title><id>tag:1</id><updated>2026-10-01T08:00:00Z</updated><link rel="enclosure" href="https://a.mx/1.mp3"/></entry></feed>"""
        val e = FeedParser.parse(atom.byteInputStream(), imported).episodes.single()
        assertEquals("tag:1", e.id); assertEquals("https://a.mx/1.mp3", e.audioUrl); assertTrue(e.publishedAt > 0)
    }

    @Test fun parsesCommonDateFormats() {
        assertTrue(FeedParser.parseDate("Mon, 5 Oct 2026 10:00:00 GMT") > 0)
        assertTrue(FeedParser.parseDate("2026-10-05T10:00:00.000+02:00") > 0)
        assertEquals(0L, FeedParser.parseDate("ayer"))
    }
}
