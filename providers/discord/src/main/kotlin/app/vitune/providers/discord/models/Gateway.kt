package app.vitune.providers.discord.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
internal data class GatewayPayload(
    val op: Int,
    val d: JsonElement? = null,
    val s: Int? = null,
    val t: String? = null
)

@Serializable
internal data class Presence(
    val activities: List<Activity>,
    val status: String = "online",
    val since: Long = 0,
    val afk: Boolean = true
)

@Serializable
internal data class Identify(
    val token: String,
    val properties: Properties,
    val presence: Presence,
    val intents: Int = 0,
    val compress: Boolean = false
) {
    @Serializable
    data class Properties(
        val os: String = "Android",
        val browser: String = "Discord Android",
        val device: String = "ViTune"
    )
}

@Serializable
internal data class ExternalAssetsBody(val urls: List<String>)

@Serializable
internal data class ExternalAsset(
    val url: String,
    @SerialName("external_asset_path")
    val externalAssetPath: String
)
