package app.vitune.android.utils

import android.os.Bundle
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import app.vitune.android.preferences.AppearancePreferences
import app.vitune.core.ui.utils.songBundle
import kotlin.time.Duration

private const val CUSTOM_QUEUE_KEY = "isFromCustomQueue"
private const val QUEUE_LABEL_KEY = "queueLabel"

val Player.currentWindow: Timeline.Window?
    get() = if (mediaItemCount == 0) null else currentTimeline[currentMediaItemIndex]

inline val Timeline.windows: List<Timeline.Window>
    get() = List(windowCount) { this[it] }

inline val Timeline.mediaItems: List<MediaItem>
    get() = windows.map { it.mediaItem }

val Player.shouldBePlaying: Boolean
    get() = !(playbackState == Player.STATE_ENDED || !playWhenReady)

fun Player.removeMediaItems(range: IntRange) = removeMediaItems(range.first, range.last + 1)

fun Player.safeClearQueue() {
    if (currentMediaItemIndex > 0) removeMediaItems(0 until currentMediaItemIndex)
    if (currentMediaItemIndex < mediaItemCount - 1)
        removeMediaItems(currentMediaItemIndex + 1 until mediaItemCount)
}

fun Player.seamlessPlay(mediaItem: MediaItem) =
    if (mediaItem.mediaId == currentMediaItem?.mediaId) safeClearQueue() else forcePlay(mediaItem)

private fun MediaItem.withCustomQueueTag() = buildUpon()
    .setMediaMetadata(
        androidx.media3.common.MediaMetadata.Builder()
            .setTitle(mediaMetadata.title)
            .setArtist(mediaMetadata.artist)
            .setAlbumTitle(mediaMetadata.albumTitle)
            .setArtworkUri(mediaMetadata.artworkUri)
            .setExtras(
                Bundle(mediaMetadata.extras)
                    .apply { putBoolean(CUSTOM_QUEUE_KEY, true) }
            )
            .build()
    )
    .build()

fun MediaItem.withQueueLabel(queueLabel: String?) =
    if (queueLabel.isNullOrBlank()) this
    else buildUpon()
        .setMediaMetadata(
            androidx.media3.common.MediaMetadata.Builder()
                .setTitle(mediaMetadata.title)
                .setArtist(mediaMetadata.artist)
                .setAlbumTitle(mediaMetadata.albumTitle)
                .setArtworkUri(mediaMetadata.artworkUri)
                .setExtras(
                    Bundle(mediaMetadata.extras)
                        .apply { putString(QUEUE_LABEL_KEY, queueLabel) }
                )
                .build()
        )
        .build()

fun List<MediaItem>.withQueueLabel(queueLabel: String?) =
    map { it.withQueueLabel(queueLabel) }

private fun MediaItem.isCustomQueued() = mediaMetadata.extras?.getBoolean(CUSTOM_QUEUE_KEY) == true

private fun Player.customQueueStartIndex() = currentMediaItemIndex + 1

private fun Player.customQueueCount() = currentTimeline.mediaItems
    .drop(customQueueStartIndex())
    .takeWhile { it.isCustomQueued() }
    .size

private fun Player.playlistQueueStartIndex() = customQueueStartIndex() + customQueueCount()

fun Player.customQueueItems(): List<MediaItem> = currentTimeline.mediaItems
    .drop(customQueueStartIndex())
    .takeWhile { it.isCustomQueued() }

fun Player.playlistQueueItems(): List<MediaItem> = currentTimeline.mediaItems
    .drop(playlistQueueStartIndex())

fun Player.clearCustomQueue() {
    val startIndex = customQueueStartIndex()
    val count = customQueueCount()
    if (count > 0) removeMediaItems(startIndex, startIndex + count)
}

fun Player.moveCustomQueueItem(fromIndex: Int, toIndex: Int) {
    if (fromIndex == toIndex) return
    val startIndex = customQueueStartIndex()
    val from = startIndex + fromIndex
    val to = startIndex + toIndex
    val item = getMediaItemAt(from)
    removeMediaItem(from)
    addMediaItem(to, item)
}

fun Player.movePlaylistQueueItem(fromIndex: Int, toIndex: Int) {
    if (fromIndex == toIndex) return
    val startIndex = playlistQueueStartIndex()
    val from = startIndex + fromIndex
    val to = startIndex + toIndex
    val item = getMediaItemAt(from)
    removeMediaItem(from)
    addMediaItem(to, item)
}

fun Player.removeCustomQueueItem(index: Int) {
    removeMediaItem(customQueueStartIndex() + index)
}

fun Player.removePlaylistQueueItem(index: Int) {
    removeMediaItem(playlistQueueStartIndex() + index)
}

fun Player.playAtIndex(index: Int) {
    seekTo(index, C.TIME_UNSET)
    playWhenReady = true
}

fun Player.shuffleCustomQueue() {
    val startIndex = customQueueStartIndex()
    val count = customQueueCount()
    if (count <= 1) return

    val shuffled = currentTimeline.mediaItems
        .subList(startIndex, startIndex + count)
        .shuffled()

    removeMediaItems(startIndex, startIndex + count)
    addMediaItems(startIndex, shuffled)
}

fun Player.shufflePlaylistQueue() {
    val startIndex = playlistQueueStartIndex()
    val count = mediaItemCount - startIndex
    if (count <= 1) return

    val shuffled = currentTimeline.mediaItems
        .subList(startIndex, startIndex + count)
        .shuffled()

    removeMediaItems(startIndex, startIndex + count)
    addMediaItems(startIndex, shuffled)
}

fun Player.forcePlay(mediaItem: MediaItem) {
    setMediaItem(mediaItem, true)
    playWhenReady = true
    prepare()
}

fun Player.forcePlayAtIndex(
    items: List<MediaItem>,
    index: Int
) {
    if (items.isEmpty()) return

    setMediaItems(items, index, C.TIME_UNSET)
    playWhenReady = true
    prepare()
}

fun Player.forcePlayFromBeginning(items: List<MediaItem>) = forcePlayAtIndex(items, 0)

fun Player.forceSeekToPrevious(
    hideExplicit: Boolean = AppearancePreferences.hideExplicit,
    seekToStart: Boolean = true
): Unit {
    when {
        seekToStart && currentPosition > maxSeekToPreviousPosition -> seekToPrevious()
        currentMediaItem?.isCustomQueued() == true -> if (hideExplicit) {
            if (mediaItemCount <= 1) forceSeekToPrevious(hideExplicit = false)
            else {
                var i = currentMediaItemIndex - 1
                while (
                    i !in (0 until mediaItemCount) ||
                    getMediaItemAt(i).mediaMetadata.extras?.songBundle?.explicit == true
                ) {
                    if (i <= 0) i = mediaItemCount - 1 else i--
                }
                seekTo(i, C.TIME_UNSET)
            }
        } else {
            if (hasPreviousMediaItem()) seekToPreviousMediaItem()
            else if (mediaItemCount > 0) seekTo(currentMediaItemIndex, C.TIME_UNSET)
        }

        else -> {
            var i = currentMediaItemIndex - 1
            while (
                i >= 0 &&
                hideExplicit &&
                getMediaItemAt(i).mediaMetadata.extras?.songBundle?.explicit == true
            ) i--

            when {
                i >= 0 -> seekTo(i, C.TIME_UNSET)
                mediaItemCount > 0 -> seekTo(currentMediaItemIndex, C.TIME_UNSET)
            }
        }
    }
}

fun Player.forceSeekToNext() =
    if (hasNextMediaItem()) seekToNext() else seekTo(0, C.TIME_UNSET)

fun Player.addNext(mediaItem: MediaItem) {
    when (playbackState) {
        Player.STATE_IDLE, Player.STATE_ENDED -> forcePlay(mediaItem.withCustomQueueTag())
        else -> addMediaItem(customQueueStartIndex(), mediaItem.withCustomQueueTag())
    }
}

fun Player.enqueue(mediaItem: MediaItem) {
    when (playbackState) {
        Player.STATE_IDLE, Player.STATE_ENDED -> forcePlay(mediaItem.withCustomQueueTag())
        else -> addMediaItem(customQueueStartIndex() + customQueueCount(), mediaItem.withCustomQueueTag())
    }
}

fun Player.enqueue(mediaItems: List<MediaItem>) = when (playbackState) {
    Player.STATE_IDLE, Player.STATE_ENDED -> forcePlayFromBeginning(mediaItems.map(MediaItem::withCustomQueueTag))
    else -> addMediaItems(
        customQueueStartIndex() + customQueueCount(),
        mediaItems.map(MediaItem::withCustomQueueTag)
    )
}

fun Player.findNextMediaItemById(mediaId: String): MediaItem? = runCatching {
    for (i in currentMediaItemIndex until mediaItemCount) {
        if (getMediaItemAt(i).mediaId == mediaId) return getMediaItemAt(i)
    }
    return null
}.getOrNull()

fun Player.setPlaybackPitch(pitch: Float) {
    playbackParameters = PlaybackParameters(playbackParameters.speed, pitch)
}

operator fun Timeline.get(
    index: Int,
    window: Timeline.Window = Timeline.Window(),
    positionProjection: Duration = Duration.ZERO
): Timeline.Window = getWindow(index, window, positionProjection.inWholeMicroseconds)
