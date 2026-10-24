package app.vitune.providers.binimum

import app.vitune.providers.common.models.WordSyncedLyrics
import app.vitune.providers.utils.runCatchingCancellable
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.http.HttpHeaders
import io.ktor.serialization.kotlinx.json.json
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.roundToLong
import kotlin.time.Duration

private const val AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36"

object Binimum {
    private val client by lazy {
        HttpClient(OkHttp) {
            install(HttpTimeout) {
                requestTimeoutMillis = 5000
                connectTimeoutMillis = 5000
                socketTimeoutMillis = 5000
            }

            install(ContentNegotiation) {
                json(
                    Json {
                        isLenient = true
                        ignoreUnknownKeys = true
                        explicitNulls = false
                    }
                )
            }

            defaultRequest {
                url("https://lyrics-api.binimum.org")
                header(HttpHeaders.UserAgent, AGENT)
            }
        }
    }

    suspend fun lyrics(
        artist: String,
        title: String,
        duration: Duration,
        album: String? = null
    ) = runCatchingCancellable {
        val response = client.get("/") {
            parameter("track", title)
            parameter("artist", artist)
            if (!album.isNullOrBlank()) parameter("album", album)
            if (duration.inWholeSeconds > 0) parameter("duration", duration.inWholeSeconds)
        }.body<SearchResponse>()

        val lyricsUrl = response.results
            .firstOrNull {
                it.timingType == "word" &&
                    !it.lyricsUrl.isNullOrBlank()
            }
            ?.lyricsUrl
            ?: return@runCatchingCancellable null

        client.get(lyricsUrl).bodyAsText().let(Binimum::parse)
    }

    private fun parse(raw: String): WordSyncedLyrics? = runCatching {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = false
            isIgnoringComments = true
            isCoalescing = true
        }

        val document = factory.newDocumentBuilder().parse(
            org.xml.sax.InputSource(StringReader(raw))
        )

        val oppositeAgents = document.getElementsByTagName("ttm:agent").let { nodes ->
            buildList {
                for (index in 0 until nodes.length) {
                    val agent = nodes.item(index) as? Element ?: continue
                    if (agent.getAttribute("type") == "person") add(agent.getAttribute("xml:id"))
                }
            }.filterIndexed { index, _ -> index % 2 == 1 }.toSet()
        }

        val lines = document.getElementsByTagName("p").let { nodes ->
            buildList {
                for (index in 0 until nodes.length) {
                    (nodes.item(index) as? Element)
                        ?.toLine(oppositeAgents)
                        ?.let(::add)
                }
            }
        }

        lines.takeIf { it.isNotEmpty() }?.let { WordSyncedLyrics(it) }
    }.getOrNull()

    private fun Element.toLine(oppositeAgents: Set<String>): WordSyncedLyrics.Line? {
        val songPart = parentElement("div")?.getAttribute("itunes:songPart")?.takeIf { it.isNotBlank() }
        val begin = getAttribute("begin").takeIf { it.isNotBlank() }?.toTimestampMillis()
        val end = getAttribute("end").takeIf { it.isNotBlank() }?.toTimestampMillis()
        val words = mutableListOf<WordSyncedLyrics.Word>()

        parseChildren(
            node = this,
            background = false,
            words = words
        )

        val lineBegin = begin ?: words.minOfOrNull { it.begin } ?: return null
        val lineEnd = end ?: words.maxOfOrNull { it.end } ?: return null

        return WordSyncedLyrics.Line(
            begin = lineBegin,
            end = lineEnd,
            words = words,
            songPart = songPart,
            oppositeAligned = getAttribute("ttm:agent") in oppositeAgents
        )
    }

    private fun parseChildren(
        node: Element,
        background: Boolean,
        words: MutableList<WordSyncedLyrics.Word>
    ): Int {
        var lastWordIndex = -1

        for (index in 0 until node.childNodes.length) {
            when (val child = node.childNodes.item(index)) {
                is Element -> {
                    if (child.tagName != "span") continue

                    val role = child.getAttribute("ttm:role")
                    val isBackground = background || role == "x-bg"
                    val hasChildSpans = child.childNodes.hasSpanChild()

                    if (hasChildSpans) {
                        val nestedLastWordIndex = parseChildren(
                            node = child,
                            background = isBackground,
                            words = words
                        )
                        if (nestedLastWordIndex >= 0) lastWordIndex = nestedLastWordIndex
                    } else {
                        child.toWord(background = isBackground)?.let { word ->
                            words += word
                            lastWordIndex = words.lastIndex
                        }
                    }
                }

                is Node -> {
                    if (child.nodeType != Node.TEXT_NODE || lastWordIndex < 0) continue

                    val separator = child.nodeValue.takeIf { value ->
                        value.isNotEmpty() && value.none { it == '\n' || it == '\r' }
                    } ?: continue

                    words[lastWordIndex] = words[lastWordIndex].copy(separator = separator)
                }
            }
        }

        return lastWordIndex
    }

    private fun Element.toWord(background: Boolean): WordSyncedLyrics.Word? {
        val begin = getAttribute("begin").takeIf { it.isNotBlank() }?.toTimestampMillis() ?: return null
        val end = getAttribute("end").takeIf { it.isNotBlank() }?.toTimestampMillis() ?: return null
        val text = textContent
            .replace("\r", "")
            .replace("\n", "")
            .trim()

        return if (text.isBlank()) null else WordSyncedLyrics.Word(
            begin = begin,
            end = end,
            text = text,
            background = background
        )
    }

    private fun Element.parentElement(tagName: String): Element? {
        var current = parentNode
        while (current is Element) {
            if (current.tagName == tagName) return current
            current = current.parentNode
        }
        return null
    }

    private fun String.toTimestampMillis(): Long = if (contains(':')) {
        val (minutes, seconds) = split(":", limit = 2)
        val (wholeSeconds, fraction) = seconds.split(".", limit = 2).let {
            it.first() to it.getOrNull(1).orEmpty()
        }
        (minutes.toLong() * 60_000) +
            (wholeSeconds.toLong() * 1_000) +
            fraction.padEnd(3, '0').take(3).toLong()
    } else (toDouble() * 1000.0).roundToLong()

    private fun org.w3c.dom.NodeList.hasSpanChild(): Boolean {
        for (index in 0 until length) {
            val node = item(index)
            if (node is Element && node.tagName == "span") return true
        }
        return false
    }

    @Serializable
    private data class SearchResponse(
        val total: Int,
        val source: String? = null,
        val results: List<Result> = emptyList()
    ) {
        @Serializable
        data class Result(
            val id: String? = null,
            @SerialName("track_name") val trackName: String? = null,
            @SerialName("artist_name") val artistName: String? = null,
            @SerialName("album_name") val albumName: String? = null,
            val duration: Long? = null,
            val isrc: String? = null,
            @SerialName("timing_type") val timingType: String? = null,
            @SerialName("lyricsUrl") val lyricsUrl: String? = null
        )
    }
}
