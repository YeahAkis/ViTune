package app.vitune.android.utils

import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import app.vitune.android.Database
import app.vitune.android.models.Lyrics
import app.vitune.android.service.LOCAL_KEY_PREFIX
import app.vitune.providers.binimum.Binimum
import app.vitune.providers.innertube.Innertube
import app.vitune.providers.innertube.models.bodies.NextBody
import app.vitune.providers.innertube.requests.lyrics
import app.vitune.providers.kugou.KuGou
import app.vitune.providers.lrclib.LrcLib
import app.vitune.providers.lrclib.LrcParser
import app.vitune.providers.lrclib.toLrcFile
import app.vitune.core.ui.utils.songBundle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.milliseconds

object LyricsFetcher {
    private const val PROVIDER_TIMEOUT_MS = 7_000L
    private const val DURATION_WAIT_MS = 5_000L
    private const val DURATION_POLL_MS = 100L

    private val scope = CoroutineScope(
        Dispatchers.IO + SupervisorJob() + CoroutineName("LyricsFetcher")
    )
    private val inFlight = ConcurrentHashMap<String, Deferred<Lyrics?>>()

    fun prefetch(mediaItem: MediaItem) {
        scope.launch {
            runCatching {
                ensure(
                    mediaId = mediaItem.mediaId,
                    mediaMetadata = mediaItem.mediaMetadata,
                    ensureSongInserted = { Database.insert(mediaItem) },
                    playerDuration = { C.TIME_UNSET }
                )
            }.exceptionOrNull()?.let { if (it is CancellationException) throw it }
        }
    }

    suspend fun ensure(
        mediaId: String,
        mediaMetadata: MediaMetadata,
        ensureSongInserted: () -> Unit,
        playerDuration: suspend () -> Long
    ): Lyrics? = inFlight.computeIfAbsent(mediaId) {
        scope.async {
            try {
                fetch(
                    mediaId = mediaId,
                    mediaMetadata = mediaMetadata,
                    ensureSongInserted = ensureSongInserted,
                    playerDuration = playerDuration
                )
            } finally {
                inFlight.remove(mediaId)
            }
        }
    }.await()

    private suspend fun fetch(
        mediaId: String,
        mediaMetadata: MediaMetadata,
        ensureSongInserted: () -> Unit,
        playerDuration: suspend () -> Long
    ): Lyrics? {
        val current = Database.lyrics(mediaId).firstOrNull()
        if (current?.wordSyncedChecked != null) return current

        val duration = resolveDuration(mediaMetadata, playerDuration) ?: return null

        val resolved = resolve(
            mediaId = mediaId,
            artist = mediaMetadata.artist?.toString().orEmpty(),
            title = mediaMetadata.title?.toString().orEmpty().let {
                if (mediaId.startsWith(LOCAL_KEY_PREFIX)) it.substringBeforeLast('.').trim()
                else it
            },
            album = mediaMetadata.albumTitle?.toString(),
            duration = duration,
            currentLyrics = current
        )

        runCatching {
            ensureSongInserted()
            Database.upsert(resolved)
        }

        return resolved
    }

    private suspend fun resolveDuration(
        mediaMetadata: MediaMetadata,
        playerDuration: suspend () -> Long
    ): Long? {
        mediaMetadata.extras?.songBundle?.durationText?.toDurationMs()?.let { return it }

        var waited = 0L
        while (waited <= DURATION_WAIT_MS) {
            val duration = playerDuration()
            if (duration != C.TIME_UNSET && duration > 0) return duration

            delay(DURATION_POLL_MS)
            waited += DURATION_POLL_MS
        }

        return null
    }

    private fun String.toDurationMs(): Long? {
        val parts = split(':').map { it.trim().toLongOrNull() ?: return null }

        val seconds = when (parts.size) {
            2 -> parts[0] * 60 + parts[1]
            3 -> parts[0] * 3600 + parts[1] * 60 + parts[2]
            else -> return null
        }

        return (seconds * 1000).takeIf { it > 0 }
    }

    private suspend fun <T> safely(block: suspend () -> T?): T? = try {
        withTimeoutOrNull(PROVIDER_TIMEOUT_MS) { block() }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Throwable) {
        null
    }

    private suspend fun resolve(
        mediaId: String,
        artist: String,
        title: String,
        album: String?,
        duration: Long,
        currentLyrics: Lyrics?
    ): Lyrics = supervisorScope {
        val base = currentLyrics ?: Lyrics(songId = mediaId, fixed = null, synced = null)
        val cached = base.copy(wordSyncedChecked = false)

        val wordSynced = if (base.wordSyncedChecked == null) async {
            safely {
                Binimum.lyrics(
                    artist = artist,
                    title = title,
                    duration = duration.milliseconds,
                    album = album
                )?.getOrNull()
            }
        } else null

        val needsSynced = cached.synced == null
        val syncedFetch = if (needsSynced) async {
            fetchSyncedLyrics(
                artist = artist,
                title = title,
                duration = duration,
                album = album
            )
        } else null

        wordSynced?.await()?.let { found ->
            syncedFetch?.cancel()

            return@supervisorScope base.copy(
                fixed = found.text,
                synced = null,
                wordSynced = found,
                wordSyncedChecked = true
            )
        }

        cached.synced?.let { synced ->
            if (synced.isNotBlank()) return@supervisorScope cached.copy(
                fixed = cached.fixed ?: synced.toPlainLyrics() ?: cached.fixed,
                synced = synced
            )

            if (cached.fixed != null) return@supervisorScope cached.copy(synced = synced)
        }

        val synced = syncedFetch?.await()

        if (synced != null) return@supervisorScope cached.copy(
            fixed = synced.toPlainLyrics() ?: cached.fixed ?: synced,
            synced = synced
        )

        cached.fixed?.takeIf { it.isNotBlank() }?.let { fixed ->
            return@supervisorScope cached.copy(fixed = fixed, synced = "")
        }

        cached.copy(
            fixed = fetchFixedLyrics(
                mediaId = mediaId,
                artist = artist,
                title = title,
                duration = duration,
                album = album
            ) ?: "",
            synced = ""
        )
    }

    private suspend fun fetchSyncedLyrics(
        artist: String,
        title: String,
        duration: Long,
        album: String?
    ): String? = supervisorScope {
        val trimmedTitle = title.split("(")[0].trim()

        val exact = async {
            safely {
                LrcLib.bestLyrics(
                    artist = artist,
                    title = title,
                    duration = duration.milliseconds,
                    album = album
                )?.map { it?.text }?.getOrNull()
            }
        }
        val trimmed = if (trimmedTitle.isNotEmpty() && trimmedTitle != title) async {
            safely {
                LrcLib.bestLyrics(
                    artist = artist,
                    title = trimmedTitle,
                    duration = duration.milliseconds,
                    album = album
                )?.map { it?.text }?.getOrNull()
            }
        } else null
        val kugou = async {
            safely {
                KuGou.lyrics(
                    artist = artist,
                    title = title,
                    duration = duration / 1000
                )?.map { it?.value }?.getOrNull()
            }
        }

        val result = exact.await() ?: trimmed?.await() ?: kugou.await()
        listOfNotNull(exact, trimmed, kugou).forEach { it.cancel() }
        result
    }

    private suspend fun fetchFixedLyrics(
        mediaId: String,
        artist: String,
        title: String,
        duration: Long,
        album: String?
    ): String? = supervisorScope {
        val innertube = async {
            safely { Innertube.lyrics(NextBody(videoId = mediaId))?.getOrNull() }
        }
        val lrcLib = async {
            safely {
                LrcLib.bestLyrics(
                    artist = artist,
                    title = title,
                    duration = duration.milliseconds,
                    album = album,
                    synced = false
                )?.map { it?.text }?.getOrNull()
            }
        }

        val result = innertube.await() ?: lrcLib.await()
        innertube.cancel()
        lrcLib.cancel()
        result
    }

    private fun String.toPlainLyrics() =
        LrcParser.parse(this)?.toLrcFile()?.lines?.values?.joinToString("\n")
}
