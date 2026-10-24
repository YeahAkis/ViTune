package app.vitune.android.utils

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import app.vitune.providers.common.models.WordSyncedLyrics
import kotlinx.collections.immutable.ImmutableList
import kotlin.math.abs

private const val SNAP_THRESHOLD_MS = 300f
private const val CORRECTION_PER_SECOND = 4f

@Stable
class WordSynchronizedLyrics(
    val lines: ImmutableList<WordSyncedLyrics.Line>,
    private val positionProvider: () -> Long,
    private val isPlayingProvider: () -> Boolean
) {
    var index by mutableIntStateOf(currentIndex(positionProvider()))
        private set

    var position by mutableFloatStateOf(positionProvider().toFloat())
        private set

    private var lastFrameNanos = 0L

    private fun currentIndex(position: Long): Int {
        var index = -1
        for ((lineIndex, line) in lines.withIndex()) {
            if (line.begin >= position) break
            index = lineIndex
        }
        return if (index == -1) 0 else index
    }

    fun onFrame(frameNanos: Long): Boolean {
        val reported = positionProvider().toFloat()
        val deltaMs = if (lastFrameNanos == 0L) 0f else (frameNanos - lastFrameNanos) / 1_000_000f
        lastFrameNanos = frameNanos

        position = if (!isPlayingProvider()) reported else {
            val predicted = position + deltaMs
            val error = reported - predicted
            if (abs(error) > SNAP_THRESHOLD_MS) reported
            else predicted + error * (deltaMs / 1000f * CORRECTION_PER_SECOND).coerceAtMost(1f)
        }

        val newIndex = currentIndex(position.toLong())
        val indexChanged = newIndex != index
        index = newIndex
        return indexChanged
    }
}
