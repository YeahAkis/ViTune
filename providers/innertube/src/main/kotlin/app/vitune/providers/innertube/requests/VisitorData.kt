package app.vitune.providers.innertube.requests

import app.vitune.providers.innertube.Innertube
import app.vitune.providers.innertube.models.Context
import app.vitune.providers.utils.runCatchingCancellable
import io.ktor.client.call.body
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable

@Serializable
private data class VisitorIdBody(
    val context: Context
)

@Serializable
private data class VisitorIdResponse(
    val responseContext: ResponseContext? = null
) {
    @Serializable
    data class ResponseContext(
        val visitorData: String? = null
    )
}

private val visitorDataMutex = Mutex()

@Volatile
private var cachedVisitorData: String? = null

suspend fun Innertube.visitorData(
    context: Context = Context.DefaultVisionOS,
    refresh: Boolean = false
): String? {
    if (!refresh) cachedVisitorData?.let { return it }

    return visitorDataMutex.withLock {
        if (!refresh) cachedVisitorData?.let { return@withLock it }

        runCatchingCancellable {
            client.post(VISITOR_ID) {
                setBody(VisitorIdBody(context = context))
                context.apply()
            }.body<VisitorIdResponse>().responseContext?.visitorData
        }
            ?.onFailure { logger.warn("Failed to fetch visitor data", it) }
            ?.getOrNull()
            ?.also { cachedVisitorData = it }
            ?: cachedVisitorData
    }
}
