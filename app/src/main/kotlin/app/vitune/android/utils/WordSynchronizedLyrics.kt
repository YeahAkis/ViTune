package app.vitune.android.utils

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.setValue
import app.vitune.providers.common.models.WordSyncedLyrics
import kotlinx.collections.immutable.ImmutableList

@Stable
class WordSynchronizedLyrics(
    val lines: ImmutableList<WordSyncedLyrics.Line>,
    private val positionProvider: () -> Long
) {
    var index by mutableIntStateOf(currentIndex(positionProvider()))
        private set

    var position by mutableLongStateOf(positionProvider())
        private set

    private fun currentIndex(position: Long): Int {
        var index = -1
        for ((lineIndex, line) in lines.withIndex()) {
            if (line.begin >= position) break
            index = lineIndex
        }
        return if (index == -1) 0 else index
    }

    fun update(): Boolean {
        val newPosition = positionProvider()
        val newIndex = currentIndex(newPosition)
        val indexChanged = newIndex != index
        index = newIndex
        position = newPosition
        return indexChanged
    }
}
