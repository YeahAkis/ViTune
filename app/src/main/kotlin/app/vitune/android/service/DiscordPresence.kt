package app.vitune.android.service

import android.util.LruCache
import androidx.media3.common.MediaItem
import app.vitune.android.preferences.DiscordPreferences
import app.vitune.android.preferences.DiscordPreferences.PauseBehavior
import app.vitune.android.utils.thumbnail
import app.vitune.providers.discord.Discord
import app.vitune.providers.discord.DiscordGateway
import app.vitune.providers.discord.models.Activity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class DiscordPresence(
    private val scope: CoroutineScope,
    private val pausedText: String
) {
    data class Playback(
        val mediaItem: MediaItem,
        val isPlaying: Boolean,
        val positionMs: Long,
        val durationMs: Long?,
        val speed: Float
    )

    private data class Options(
        val showArtist: Boolean,
        val showAlbum: Boolean,
        val showArtwork: Boolean,
        val showProgress: Boolean
    )

    private data class Settings(
        val token: String?,
        val pauseBehavior: PauseBehavior,
        val options: Options
    )

    private val playback = MutableStateFlow<Playback?>(null)
    private val images = LruCache<String, String>(IMAGE_CACHE_SIZE)

    private var job: Job? = null
    private var gateway: DiscordGateway? = null
    private var gatewayToken: String? = null
    private var lastActivity: Activity? = null

    fun update(playback: Playback?) {
        this.playback.value = playback
    }

    fun start() {
        if (job?.isActive == true) return

        val options = combine(
            DiscordPreferences.showArtistProperty.stateFlow,
            DiscordPreferences.showAlbumProperty.stateFlow,
            DiscordPreferences.showArtworkProperty.stateFlow,
            DiscordPreferences.showProgressProperty.stateFlow,
            ::Options
        )

        val settings = combine(
            DiscordPreferences.enabledProperty.stateFlow,
            DiscordPreferences.tokenProperty.stateFlow,
            DiscordPreferences.pauseBehaviorProperty.stateFlow,
            options
        ) { enabled, token, pauseBehavior, options ->
            Settings(
                token = token.takeIf { enabled && it.isNotBlank() },
                pauseBehavior = pauseBehavior,
                options = options
            )
        }

        job = scope.launch {
            combine(playback, settings, ::Pair).collectLatest { (playback, settings) ->
                render(playback, settings)
            }
        }
    }

    fun release() {
        job?.cancel()
        job = null
        stopGateway()
    }

    private suspend fun render(playback: Playback?, settings: Settings) {
        val token = settings.token
        if (token == null) {
            stopGateway()
            return
        }

        val shown = playback?.takeIf { it.isPlaying || settings.pauseBehavior == PauseBehavior.ShowPaused }
        if (shown == null) {
            if (lastActivity != null) {
                gateway?.setActivity(null)
                lastActivity = null
            }
            delay(DISCONNECT_DELAY)
            stopGateway()
            return
        }

        delay(UPDATE_DEBOUNCE)

        val activity = activityOf(shown, token, settings.options)
        if (lastActivity?.isSameAs(activity) == true && gateway?.isRunning == true) return

        gatewayFor(token).apply {
            setActivity(activity)
            start()
        }
        lastActivity = activity
    }

    private fun gatewayFor(token: String): DiscordGateway {
        gateway?.takeIf { gatewayToken == token }?.let { return it }

        stopGateway()
        return DiscordGateway(
            token = token,
            scope = scope,
            onAuthFailed = { DiscordPreferences.token = "" }
        ).also {
            gateway = it
            gatewayToken = token
        }
    }

    private fun stopGateway() {
        gateway?.stop()
        gateway = null
        gatewayToken = null
        lastActivity = null
    }

    private suspend fun activityOf(playback: Playback, token: String, options: Options): Activity {
        val metadata = playback.mediaItem.mediaMetadata
        val title = metadata.title?.toString()
        val artist = metadata.artist?.toString()?.takeIf { options.showArtist }
        val album = metadata.albumTitle?.toString()?.takeIf { options.showAlbum }
        val image = if (options.showArtwork) image(metadata.artworkUri?.toString(), token) else null

        val timestamps = if (playback.isPlaying && options.showProgress) {
            val now = System.currentTimeMillis()
            val speed = playback.speed.coerceAtLeast(MIN_SPEED)
            Activity.Timestamps(
                start = now - (playback.positionMs / speed).toLong(),
                end = playback.durationMs
                    ?.let { now + ((it - playback.positionMs) / speed).toLong() }
            )
        } else null

        return Activity(
            name = APP_NAME,
            applicationId = APPLICATION_ID,
            details = title,
            state = if (playback.isPlaying) artist else listOfNotNull(pausedText, artist).joinToString(" · "),
            timestamps = timestamps,
            assets = image?.let { Activity.Assets(largeImage = it, largeText = album) }
        )
    }

    private suspend fun image(url: String?, token: String): String? {
        if (url == null || !url.startsWith("http")) return null

        val sized = url.thumbnail(IMAGE_SIZE)
        images[sized]?.let { return it }

        return Discord.externalImage(token = token, applicationId = APPLICATION_ID, url = sized)
            ?.onFailure { it.printStackTrace() }
            ?.getOrNull()
            ?.also { images.put(sized, it) }
    }

    private fun Activity.isSameAs(other: Activity): Boolean {
        fun Long?.near(that: Long?) = this == that ||
            (this != null && that != null && abs(this - that) < TIMESTAMP_TOLERANCE_MS)

        return copy(timestamps = null) == other.copy(timestamps = null) &&
            timestamps?.start.near(other.timestamps?.start) &&
            timestamps?.end.near(other.timestamps?.end)
    }

    private companion object {
        const val APP_NAME = "ViTune"
        const val APPLICATION_ID = "1563910077906165760"

        const val IMAGE_SIZE = 512
        const val IMAGE_CACHE_SIZE = 64
        const val MIN_SPEED = 0.01f
        const val TIMESTAMP_TOLERANCE_MS = 3_000L

        val UPDATE_DEBOUNCE = 2.seconds
        val DISCONNECT_DELAY = 5.minutes
    }
}
