package com.kanso.podcast.data

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OpmlTest {
    private fun read(xml: String) = Opml.read(xml.byteInputStream())

    @Test fun pocketCastsStyleFoldersAreKept() {
        val doc = read("""<?xml version="1.0" encoding="utf-8"?><opml version="1.0"><head><title>Pocket Casts Feeds</title></head><body>
            <outline text="feeds">
              <outline type="rss" text="Suelto" xmlUrl="https://a.mx/feed" />
              <outline text="Noticias" title="Noticias">
                <outline type="rss" text="Diario" xmlUrl="https://b.mx/rss" />
                <outline type="rss" text="Semanal" xmlUrl="https://c.mx/rss" />
              </outline>
            </outline></body></opml>""")
        assertEquals(3, doc.entries.size)
        assertNull(doc.entries[0].folder)
        assertEquals("Noticias", doc.entries[1].folder)
        assertEquals("Noticias", doc.entries[2].folder)
    }

    @Test fun flatAntennaPodAndPodcastAddictExports() {
        val doc = read("""<opml version="2.0"><body>
            <outline text="Uno" title="Uno" type="rss" xmlUrl="https://uno.mx/feed" htmlUrl="https://uno.mx"/>
            <outline text="Dos" type="rss" xmlurl="https://dos.mx/feed"/>
            <outline text="Tres" type="rss" url="https://tres.mx/feed"/>
            </body></opml>""")
        assertEquals(listOf("Uno", "Dos", "Tres"), doc.entries.map { it.title })
        assertTrue(doc.entries.all { it.folder == null })
    }

    @Test fun duplicatesAcrossSchemesHostsAndSlashesAreSkipped() {
        val doc = read("""<opml><body>
            <outline type="rss" text="A" xmlUrl="http://www.a.mx/feed/"/>
            <outline type="rss" text="A2" xmlUrl="https://a.mx/feed"/>
            <outline type="rss" text="A3" xmlUrl="HTTPS://A.MX/feed"/>
            <outline type="rss" text="B" xmlUrl="https://a.mx/feed?x=1"/>
            </body></opml>""")
        assertEquals(2, doc.entries.size)
        assertEquals(2, doc.duplicates)
    }

    @Test fun appSchemesAmpersandsAndBomAreHandled() {
        val doc = read("\uFEFF  " + """<?xml version="1.0"?><opml><body>
            <outline type="rss" text="Feed" xmlUrl="feed://f.mx/rss"/>
            <outline type="rss" text="Itpc" xmlUrl="itpc://i.mx/rss"/>
            <outline type="rss" text="Amp" xmlUrl="https://q.mx/rss?a=1&b=2"/>
            <outline type="rss" text="Mal" xmlUrl="ftp://x.mx/rss"/>
            <outline type="rss" text="Vacío"/>
            </body></opml>""")
        assertEquals(listOf("https://f.mx/rss", "https://i.mx/rss", "https://q.mx/rss?a=1&b=2"), doc.entries.map { it.feedUrl })
        assertEquals(2, doc.invalid)
    }

    @Test(expected = Exception::class) fun nonOpmlFails() { read("<html><body>hola</body></html>") }

    @Test fun normalizeUrl() {
        assertEquals("https://x.mx/rss", Opml.normalizeUrl("feed:https://x.mx/rss"))
        assertEquals("https://x.mx/rss", Opml.normalizeUrl("x.mx/rss"))
        assertEquals("http://x.mx/rss", Opml.normalizeUrl(" http://x.mx/rss "))
        assertNull(Opml.normalizeUrl("javascript:alert(1)"))
        assertNull(Opml.normalizeUrl(""))
    }

    @Test fun exportRoundTripKeepsFoldersAndEscapes() {
        val entries = listOf(OpmlEntry("https://a.mx/feed?a=1&b=2", "A & \"B\""), OpmlEntry("https://c.mx/feed", "C", "Noticias"), OpmlEntry("https://d.mx/feed", "D", "noticias"))
        val xml = Opml.export(entries)
        assertTrue(xml.contains("&amp;b=2")); assertTrue(xml.contains("A &amp; &quot;B&quot;"))
        val back = read(xml)
        assertEquals(3, back.entries.size)
        assertEquals("https://a.mx/feed?a=1&b=2", back.entries.first { it.title.startsWith("A") }.feedUrl)
        assertEquals("Noticias", back.entries.first { it.title == "C" }.folder)
    }
}
