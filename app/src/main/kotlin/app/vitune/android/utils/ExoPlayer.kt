@file:OptIn(UnstableApi::class)

package app.vitune.android.utils

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import java.io.EOFException
import java.io.IOException
import kotlin.math.pow

class RangeHandlerDataSourceFactory(private val parent: DataSource.Factory) : DataSource.Factory {
    class Source(private val parent: DataSource) : DataSource by parent {
        override fun open(dataSpec: DataSpec) = runCatching {
            parent.open(dataSpec)
        }.getOrElse { e ->
            if (
                e.findCause<EOFException>() != null ||
                e.findCause<InvalidResponseCodeException>()?.responseCode == 416
            ) parent.open(
                dataSpec
                    .buildUpon()
                    .setHttpRequestHeaders(
                        dataSpec.httpRequestHeaders.filter {
                            it.key.equals("range", ignoreCase = true)
                        }
                    )
                    .setLength(C.LENGTH_UNSET.toLong())
                    .build()
            )
            else throw e
        }

        override fun getResponseHeaders(): Map<String, List<String>> = parent.responseHeaders
    }

    override fun createDataSource() = Source(parent.createDataSource())
}

class CatchingDataSourceFactory(
    private val parent: DataSource.Factory,
    private val onError: ((Throwable) -> Unit)?
) : DataSource.Factory {
    inner class Source(private val parent: DataSource) : DataSource by parent {
        private fun handle(ex: Throwable): Nothing {
            ex.printStackTrace()

            when (ex) {
                is PlaybackException -> throw ex
                is IOException -> throw ex.also { onError?.invoke(it) }
                else -> throw PlaybackException(
                    /* message = */ "Unknown playback error",
                    /* cause = */ ex,
                    /* errorCode = */ PlaybackException.ERROR_CODE_UNSPECIFIED
                ).also { onError?.invoke(it) }
            }
        }

        override fun open(dataSpec: DataSpec) = runCatching {
            parent.open(dataSpec)
        }.getOrElse(::handle)

        override fun read(buffer: ByteArray, offset: Int, length: Int) = runCatching {
            parent.read(buffer, offset, length)
        }.getOrElse(::handle)

        override fun getResponseHeaders(): Map<String, List<String>> = parent.responseHeaders
    }

    override fun createDataSource() = Source(parent.createDataSource())
}

fun DataSource.Factory.handleRangeErrors(): DataSource.Factory = RangeHandlerDataSourceFactory(this)
fun DataSource.Factory.handleUnknownErrors(
    onError: ((Throwable) -> Unit)? = null
): DataSource.Factory = CatchingDataSourceFactory(
    parent = this,
    onError = onError
)

class FallbackDataSourceFactory(
    private val upstream: DataSource.Factory,
    private val fallback: DataSource.Factory
) : DataSource.Factory {
    inner class Source(private val parent: DataSource) : DataSource by parent {
        override fun open(dataSpec: DataSpec) = runCatching {
            parent.open(dataSpec)
        }.getOrElse { ex ->
            ex.printStackTrace()

            runCatching {
                fallback.createDataSource().open(dataSpec)
            }.getOrElse { fallbackEx ->
                fallbackEx.printStackTrace()

                throw ex
            }
        }

        override fun getResponseHeaders(): Map<String, List<String>> = parent.responseHeaders
    }

    override fun createDataSource() = Source(upstream.createDataSource())
}

fun DataSource.Factory.withFallback(
    fallbackFactory: DataSource.Factory
): DataSource.Factory = FallbackDataSourceFactory(this, fallbackFactory)

fun DataSource.Factory.withFallback(
    context: Context,
    resolver: ResolvingDataSource.Resolver
) = withFallback(ResolvingDataSource.Factory(DefaultDataSource.Factory(context), resolver))

class RetryingDataSourceFactory(
    private val parent: DataSource.Factory,
    private val maxRetries: Int,
    private val printStackTrace: Boolean,
    private val exponential: Boolean,
    private val predicate: (Throwable) -> Boolean
) : DataSource.Factory {
    inner class Source(private val parent: DataSource) : DataSource by parent {
        override fun open(dataSpec: DataSpec): Long {
            var lastException: Throwable? = null
            var retries = 0
            while (retries < maxRetries) {
                if (retries > 0) Log.d(TAG, "Retry $retries of $maxRetries fetching datasource")

                @Suppress("TooGenericExceptionCaught")
                return try {
                    parent.open(dataSpec)
                } catch (ex: Throwable) {
                    lastException = ex
                    if (printStackTrace) Log.e(
                        /* tag = */ TAG,
                        /* msg = */ "Exception caught by retry mechanism",
                        /* tr = */ ex
                    )
                    if (predicate(ex)) {
                        val time = if (exponential) 1000L * 2.0.pow(retries).toLong() else 2500L
                        Log.d(TAG, "Retry policy accepted retry, sleeping for $time milliseconds")
                        Thread.sleep(time)
                        retries++
                        continue
                    }
                    Log.e(
                        TAG,
                        "Retry policy declined retry, throwing the last exception..."
                    )
                    throw ex
                }
            }
            Log.e(
                TAG,
                "Max retries $maxRetries exceeded, throwing the last exception..."
            )
            throw lastException!!
        }

        override fun getResponseHeaders(): Map<String, List<String>> = parent.responseHeaders
    }

    override fun createDataSource() = Source(parent.createDataSource())
}

// TODO: fix this mess
inline fun <reified T : Throwable> DataSource.Factory.retryIf(
    maxRetries: Int = 5,
    printStackTrace: Boolean = false,
    exponential: Boolean = true
) = retryIf(maxRetries, printStackTrace, exponential) { ex -> ex.findCause<T>() != null }

private const val TAG = "DataSource.Factory"

fun DataSource.Factory.retryIf(
    maxRetries: Int = 5,
    printStackTrace: Boolean = false,
    exponential: Boolean = true,
    predicate: (Throwable) -> Boolean
): DataSource.Factory = RetryingDataSourceFactory(this, maxRetries, printStackTrace, exponential, predicate)

val Cache.asDataSource: CacheDataSource.Factory get() = CacheDataSource.Factory().setCache(this)

class UserAgentOverridingDataSourceFactory(
    private val context: Context,
    private val connectTimeoutMs: Int = 16_000,
    private val readTimeoutMs: Int = 8_000
) : DataSource.Factory {
    inner class Source : DataSource {
        private var inner: DataSource? = null
        private var uri: Uri? = null
        private var headers: Map<String, List<String>> = emptyMap()
        private var transferListeners = mutableListOf<TransferListener>()

        override fun addTransferListener(transferListener: TransferListener) {
            transferListeners.add(transferListener)
        }

        override fun open(dataSpec: DataSpec): Long {
            Log.d("UserAgentOverridingDS", "open() called, position=${dataSpec.position}, uri=${dataSpec.uri}")
            val ua = dataSpec.httpRequestHeaders["User-Agent"]
            val cleanSpec = if (ua != null)
                dataSpec.buildUpon()
                    .setHttpRequestHeaders(
                        dataSpec.httpRequestHeaders.filterKeys {
                            !it.equals("User-Agent", ignoreCase = true)
                        }
                    )
                    .build()
            else dataSpec

            val httpSource = DefaultHttpDataSource.Factory()
                .setConnectTimeoutMs(connectTimeoutMs)
                .setReadTimeoutMs(readTimeoutMs)
                .apply { if (ua != null) setUserAgent(ua) }
                .createDataSource()

            val source = DefaultDataSource(context, httpSource)
            transferListeners.forEach { source.addTransferListener(it) }
            inner = source
            return source.open(cleanSpec).also {
                uri = source.uri
                headers = source.responseHeaders
            }
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int) =
            inner?.read(buffer, offset, length) ?: throw IllegalStateException("Not opened")

        override fun getUri(): Uri? = uri
        override fun getResponseHeaders() = headers
        override fun close() = inner?.close() ?: Unit
    }

    override fun createDataSource(): DataSource = Source()
}

val Context.defaultDataSource: DataSource.Factory
    get() = UserAgentOverridingDataSourceFactory(context = this)

class ChunkedDataSourceFactory(
    private val upstream: DataSource.Factory,
    private val chunkLength: Long
) : DataSource.Factory {
    inner class Source(private val upstream: DataSource) : DataSource {
        private var dataSpec: DataSpec? = null
        private var passthrough = false
        private var position = 0L
        private var end = C.LENGTH_UNSET.toLong()
        private var chunkEnd = 0L

        override fun addTransferListener(transferListener: TransferListener) =
            upstream.addTransferListener(transferListener)

        override fun open(dataSpec: DataSpec): Long {
            passthrough = dataSpec.uri.scheme?.startsWith("http") != true
            if (passthrough) return upstream.open(dataSpec)

            this.dataSpec = dataSpec
            position = dataSpec.position
            end = if (dataSpec.length == C.LENGTH_UNSET.toLong()) C.LENGTH_UNSET.toLong()
            else dataSpec.position + dataSpec.length

            openChunk()

            if (end == C.LENGTH_UNSET.toLong()) end = totalLength() ?: C.LENGTH_UNSET.toLong()
            return if (end == C.LENGTH_UNSET.toLong()) C.LENGTH_UNSET.toLong()
            else end - dataSpec.position
        }

        private fun openChunk() {
            val spec = dataSpec ?: error("Not opened")
            val length = if (end == C.LENGTH_UNSET.toLong()) chunkLength
            else (end - position).coerceAtMost(chunkLength)

            val opened = upstream.open(spec.subrange(position - spec.position, length))
            chunkEnd = position + if (opened == C.LENGTH_UNSET.toLong()) length else opened
        }

        private fun totalLength() = upstream.responseHeaders.entries
            .firstOrNull { it.key.equals("Content-Range", ignoreCase = true) }
            ?.value
            ?.firstOrNull()
            ?.substringAfterLast('/')
            ?.toLongOrNull()

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (passthrough) return upstream.read(buffer, offset, length)
            if (length == 0) return 0
            if (end != C.LENGTH_UNSET.toLong() && position >= end) return C.RESULT_END_OF_INPUT

            if (position >= chunkEnd) {
                upstream.close()
                runCatching { openChunk() }.getOrElse { e ->
                    if (end == C.LENGTH_UNSET.toLong() &&
                        e.findCause<InvalidResponseCodeException>()?.responseCode == 416
                    ) return C.RESULT_END_OF_INPUT
                    throw e
                }
            }

            val read = upstream.read(
                buffer,
                offset,
                length.toLong().coerceAtMost(chunkEnd - position).toInt()
            )
            if (read == C.RESULT_END_OF_INPUT) return C.RESULT_END_OF_INPUT

            position += read
            return read
        }

        override fun getUri(): Uri? = upstream.uri
        override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders

        override fun close() {
            dataSpec = null
            upstream.close()
        }
    }

    override fun createDataSource() = Source(upstream.createDataSource())
}

fun DataSource.Factory.chunked(chunkLength: Long): DataSource.Factory =
    ChunkedDataSourceFactory(this, chunkLength)
