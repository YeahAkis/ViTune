package app.vitune.android.utils

import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

/**
 * Minimal Downloader implementation required by NewPipeExtractor.
 * NewPipe.init(...) must be called with an instance of this before
 * any extractor (StreamInfo.getInfo, NewPipe.getService, etc.) is used.
 */
class NewPipeDownloader : Downloader() {
    override fun execute(request: Request): Response {
        val url = URL(request.url())
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = request.httpMethod()
            instanceFollowRedirects = true
            connectTimeout = 15_000
            readTimeout = 15_000
        }

        request.headers().forEach { (name, values) ->
            values.forEach { value -> connection.addRequestProperty(name, value) }
        }
        if (connection.getRequestProperty("User-Agent") == null) {
            connection.setRequestProperty(
                "User-Agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
            )
        }

        request.dataToSend()?.let { body ->
            connection.doOutput = true
            connection.outputStream.use { it.write(body) }
        }

        val responseCode = connection.responseCode
        val rawStream = if (responseCode in 400..599) connection.errorStream else connection.inputStream

        val bodyBytes = rawStream?.use { input ->
            val maybeGzip = if (connection.contentEncoding == "gzip") GZIPInputStream(input) else input
            ByteArrayOutputStream().also { out ->
                maybeGzip.copyTo(out)
            }.toByteArray()
        } ?: ByteArray(0)

        val responseBody = String(bodyBytes, Charsets.UTF_8)

        val headers = connection.headerFields
            .filterKeys { it != null }
            .mapKeys { it.key!! }

        return Response(
            responseCode,
            connection.responseMessage,
            headers,
            responseBody,
            connection.url.toString()
        )
    }
}
