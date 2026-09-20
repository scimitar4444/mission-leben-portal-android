package de.missionleben.portal.data

import org.junit.Assert.assertEquals
import org.junit.Test

class NewsFeedParserTest {
    @Test
    fun parsesRelativeMissionLebenLinksAndSortsNewestFirst() {
        val items = NewsFeedParser.parse(
            xml = """
                <rss version="2.0"><channel>
                  <item>
                    <title>Ältere Meldung</title>
                    <link>/nachrichten/alt</link>
                    <pubDate>Thu, 17 Sep 2026 11:33:00 +0000</pubDate>
                  </item>
                  <item>
                    <title>Neue Meldung</title>
                    <link>/nachrichten/neu</link>
                    <pubDate>Fri, 18 Sep 2026 14:46:00 +0000</pubDate>
                  </item>
                </channel></rss>
            """.trimIndent(),
            feedUrl = "https://www.mission-leben.de/rss.xml",
        )

        assertEquals(listOf("Neue Meldung", "Ältere Meldung"), items.map { it.title })
        assertEquals("https://www.mission-leben.de/nachrichten/neu", items.first().link)
    }

    @Test
    fun rejectsLinksOutsideMissionLebenDomains() {
        val items = NewsFeedParser.parse(
            xml = """
                <rss version="2.0"><channel><item>
                  <title>Fremde Meldung</title>
                  <link>https://example.org/phishing</link>
                  <pubDate>Fri, 18 Sep 2026 14:46:00 +0000</pubDate>
                </item></channel></rss>
            """.trimIndent(),
            feedUrl = "https://www.mission-leben.de/rss.xml",
        )

        assertEquals(emptyList<Any>(), items)
    }
}
