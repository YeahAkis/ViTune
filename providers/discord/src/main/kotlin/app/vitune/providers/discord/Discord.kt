package app.vitune.providers.discord

import app.vitune.providers.discord.models.ExternalAsset
import app.vitune.providers.discord.models.ExternalAssetsBody
import app.vitune.providers.utils.runCatchingCancellable
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

object Discord {
    internal val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    internal val httpClient by lazy {
        HttpClient(CIO) {
            install(WebSockets)
            install(ContentNegotiation) {
                json(json)
            }

            expectSuccess = true
        }
    }

    suspend fun externalImage(
        token: String,
        applicationId: String,
        url: String
    ) = runCatchingCancellable {
        httpClient.post("https://discord.com/api/v10/applications/$applicationId/external-assets") {
            header(HttpHeaders.Authorization, token)
            contentType(ContentType.Application.Json)
            setBody(ExternalAssetsBody(urls = listOf(url)))
        }.body<List<ExternalAsset>>().first().let { "mp:${it.externalAssetPath}" }
    }
}
