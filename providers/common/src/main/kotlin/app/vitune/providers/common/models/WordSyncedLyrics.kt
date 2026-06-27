package app.vitune.providers.common.models

import kotlinx.serialization.Serializable

@Serializable
data class WordSyncedLyrics(
    val lines: List<Line>
) {
    @Serializable
    data class Line(
        val begin: Long,
        val end: Long,
        val words: List<Word>,
        val songPart: String? = null
    ) {
        val text get() = words.joinToString(separator = "") { it.text + it.separator }
    }

    @Serializable
    data class Word(
        val begin: Long,
        val end: Long,
        val text: String,
        val separator: String = "",
        val background: Boolean = false
    )

    val text get() = lines.joinToString(separator = "\n") { it.text }
}
