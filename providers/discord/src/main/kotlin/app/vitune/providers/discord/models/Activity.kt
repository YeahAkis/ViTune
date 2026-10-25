package app.vitune.providers.discord.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class Activity(
    val name: String,
    val type: Int = TYPE_LISTENING,
    @SerialName("application_id")
    val applicationId: String? = null,
    val details: String? = null,
    val state: String? = null,
    val timestamps: Timestamps? = null,
    val assets: Assets? = null
) {
    @Serializable
    data class Timestamps(
        val start: Long? = null,
        val end: Long? = null
    )

    @Serializable
    data class Assets(
        @SerialName("large_image")
        val largeImage: String? = null,
        @SerialName("large_text")
        val largeText: String? = null,
        @SerialName("small_image")
        val smallImage: String? = null,
        @SerialName("small_text")
        val smallText: String? = null
    )

    companion object {
        const val TYPE_LISTENING = 2
    }
}
