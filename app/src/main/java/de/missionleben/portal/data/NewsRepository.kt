package de.missionleben.portal.data

import android.content.Context
import de.missionleben.portal.BuildConfig
import de.missionleben.portal.model.NewsItem
import java.io.StringReader
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import javax.xml.parsers.SAXParserFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.helpers.DefaultHandler

class NewsRepository(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun cached(): List<NewsItem> = runCatching {
        val values = JSONArray(preferences.getString(KEY_ITEMS, "[]"))
        buildList {
            for (index in 0 until values.length()) {
                val value = values.getJSONObject(index)
                val title = value.optString("title").trim()
                val link = value.optString("link").trim()
                if (title.isNotEmpty() && link.isNotEmpty()) {
                    add(
                        NewsItem(
                            title = title,
                            link = link,
                            publishedAtEpochSeconds = value.optLong("published_at"),
                        ),
                    )
                }
            }
        }
    }.getOrDefault(emptyList())

    suspend fun refresh(): List<NewsItem> = withContext(Dispatchers.IO) {
        val connection = URL(BuildConfig.NEWS_FEED_URL).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 8_000
            connection.readTimeout = 10_000
            connection.setRequestProperty("Accept", "application/rss+xml, application/xml;q=0.9")
            connection.setRequestProperty("User-Agent", "MissionLebenPortal/${BuildConfig.VERSION_NAME}")
            val status = connection.responseCode
            if (status !in 200..299) error("News feed returned HTTP $status")
            val xml = connection.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                val output = StringBuilder()
                val buffer = CharArray(8_192)
                while (true) {
                    val read = reader.read(buffer)
                    if (read < 0) break
                    output.append(buffer, 0, read)
                    require(output.length <= MAX_FEED_CHARACTERS) { "News feed is too large" }
                }
                output.toString()
            }
            NewsFeedParser.parse(xml, BuildConfig.NEWS_FEED_URL)
                .take(MAX_ITEMS)
                .also(::cache)
        } finally {
            connection.disconnect()
        }
    }

    private fun cache(items: List<NewsItem>) {
        val values = JSONArray()
        items.forEach { item ->
            values.put(
                JSONObject()
                    .put("title", item.title)
                    .put("link", item.link)
                    .put("published_at", item.publishedAtEpochSeconds),
            )
        }
        preferences.edit().putString(KEY_ITEMS, values.toString()).apply()
    }

    private companion object {
        const val PREFERENCES = "mission_leben_news"
        const val KEY_ITEMS = "items"
        const val MAX_ITEMS = 1
        const val MAX_FEED_CHARACTERS = 512_000
    }
}

internal object NewsFeedParser {
    fun parse(xml: String, feedUrl: String): List<NewsItem> {
        val items = mutableListOf<NewsItem>()
        val factory = SAXParserFactory.newInstance().apply {
            isNamespaceAware = false
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
            runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
            runCatching { setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false) }
        }
        val handler = object : DefaultHandler() {
            private var inItem = false
            private var field: String? = null
            private val text = StringBuilder()
            private var title = ""
            private var link = ""
            private var published = ""

            override fun startElement(uri: String?, localName: String?, qName: String?, attributes: Attributes?) {
                when (qName.orEmpty().lowercase()) {
                    "item" -> {
                        inItem = true
                        title = ""
                        link = ""
                        published = ""
                    }
                    "title", "link", "pubdate" -> if (inItem) {
                        field = qName.orEmpty().lowercase()
                        text.setLength(0)
                    }
                }
            }

            override fun characters(ch: CharArray, start: Int, length: Int) {
                if (field != null) text.append(ch, start, length)
            }

            override fun endElement(uri: String?, localName: String?, qName: String?) {
                val name = qName.orEmpty().lowercase()
                if (inItem && name == field) {
                    when (name) {
                        "title" -> title = text.toString().trim()
                        "link" -> link = text.toString().trim()
                        "pubdate" -> published = text.toString().trim()
                    }
                    field = null
                    text.setLength(0)
                }
                if (name == "item") {
                    normalizeLink(link, feedUrl)?.let { safeLink ->
                        if (title.isNotBlank()) {
                            items += NewsItem(
                                title = title,
                                link = safeLink,
                                publishedAtEpochSeconds = parseDate(published),
                            )
                        }
                    }
                    inItem = false
                    field = null
                }
            }
        }
        factory.newSAXParser().parse(InputSource(StringReader(xml)), handler)
        return items.sortedByDescending(NewsItem::publishedAtEpochSeconds)
    }

    private fun normalizeLink(value: String, feedUrl: String): String? = runCatching {
        val resolved = URI(feedUrl).resolve(value.trim()).normalize()
        val host = resolved.host?.lowercase().orEmpty()
        require(resolved.scheme.equals("https", ignoreCase = true))
        require(host == "mission-leben.de" || host.endsWith(".mission-leben.de"))
        resolved.toString()
    }.getOrNull()

    private fun parseDate(value: String): Long = runCatching {
        ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toEpochSecond()
    }.getOrDefault(0L)
}
