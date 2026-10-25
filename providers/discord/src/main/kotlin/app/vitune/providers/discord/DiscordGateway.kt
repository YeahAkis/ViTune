package app.vitune.providers.discord

import app.vitune.providers.discord.models.Activity
import app.vitune.providers.discord.models.GatewayPayload
import app.vitune.providers.discord.models.Identify
import app.vitune.providers.discord.models.Presence
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readBytes
import io.ktor.websocket.readText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.Inflater
import kotlin.random.Random
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class DiscordGateway(
    private val token: String,
    private val scope: CoroutineScope,
    private val onAuthFailed: () -> Unit
) {
    private val activity = MutableStateFlow<Activity?>(null)
    private var job: Job? = null

    @Volatile
    private var session: DefaultClientWebSocketSession? = null

    val isRunning get() = job?.isActive == true

    fun start() {
        if (isRunning) return
        job = scope.launch { run() }
    }

    fun stop() {
        val job = job ?: return
        this.job = null
        val session = session

        scope.launch(NonCancellable) {
            withTimeoutOrNull(CLOSE_TIMEOUT) { session?.close() }
            job.cancel()
        }
    }

    fun setActivity(activity: Activity?) {
        this.activity.value = activity
    }

    private suspend fun run() {
        var backoff = MIN_BACKOFF
        while (true) {
            val result = runCatching { session(onReady = { backoff = MIN_BACKOFF }) }
            currentCoroutineContext().ensureActive()

            if (result.getOrNull() == SessionEnd.AuthFailed) {
                onAuthFailed()
                return
            }
            result.exceptionOrNull()?.printStackTrace()

            delay(backoff)
            backoff = (backoff * 2).coerceAtMost(MAX_BACKOFF)
        }
    }

    private suspend fun session(onReady: () -> Unit): SessionEnd = coroutineScope {
        var end = SessionEnd.Reconnect

        Discord.httpClient.webSocket(GATEWAY_URL) {
            session = this
            val stream = ZlibStream()
            val connection = Connection(socket = this, scope = this@coroutineScope, onReady = onReady)

            try {
                for (frame in incoming) {
                    val text = stream.textOf(frame)
                    if (text != null && !connection.handle(Discord.json.decodeFromString(text))) break
                }

                if (connection.reconnectRequested) close()
                else if (closeReason.await()?.code in AUTH_FAILED_CODES) end = SessionEnd.AuthFailed
            } finally {
                session = null
                connection.cancel()
                stream.close()
            }
        }

        end
    }

    private inner class Connection(
        private val socket: DefaultClientWebSocketSession,
        private val scope: CoroutineScope,
        private val onReady: () -> Unit
    ) {
        private val sequence = AtomicInteger(-1)
        private val acked = AtomicBoolean(true)
        private var heartbeat: Job? = null
        private var presence: Job? = null
        private var identified: Activity? = null

        var reconnectRequested = false
            private set

        suspend fun handle(payload: GatewayPayload): Boolean {
            payload.s?.let(sequence::set)

            when (payload.op) {
                OP_HELLO -> hello(payload)
                OP_HEARTBEAT -> socket.sendHeartbeat(sequence.get())
                OP_HEARTBEAT_ACK -> acked.set(true)
                OP_DISPATCH -> if (payload.t == "READY") ready()
                OP_RECONNECT, OP_INVALID_SESSION -> reconnectRequested = true
            }

            return !reconnectRequested
        }

        fun cancel() {
            heartbeat?.cancel()
            presence?.cancel()
        }

        private suspend fun hello(payload: GatewayPayload) {
            val interval = payload.d?.jsonObject?.get("heartbeat_interval")
                ?.jsonPrimitive?.long ?: DEFAULT_HEARTBEAT_MS

            heartbeat?.cancel()
            heartbeat = scope.launch {
                delay((interval * Random.nextDouble()).toLong())
                while (true) {
                    if (!acked.getAndSet(false)) throw ZombieConnectionException()
                    socket.sendHeartbeat(sequence.get())
                    delay(interval)
                }
            }

            identified = activity.value
            socket.send(OP_IDENTIFY, identify(identified))
        }

        private fun ready() {
            if (presence != null) return
            onReady()

            presence = scope.launch {
                var sent = identified
                activity.collect {
                    if (it == sent) return@collect
                    socket.send(OP_PRESENCE_UPDATE, presenceOf(it))
                    sent = it
                }
            }
        }
    }

    private fun identify(activity: Activity?) = Discord.json.encodeToJsonElement(
        Identify(
            token = token,
            properties = Identify.Properties(),
            presence = presenceOf(activity)
        )
    )

    private fun presenceOf(activity: Activity?) = Presence(activities = listOfNotNull(activity))

    private suspend fun DefaultClientWebSocketSession.sendHeartbeat(sequence: Int) =
        send(OP_HEARTBEAT, if (sequence < 0) JsonNull else JsonPrimitive(sequence))

    private suspend fun DefaultClientWebSocketSession.send(op: Int, presence: Presence) =
        send(op, Discord.json.encodeToJsonElement(presence))

    private suspend fun DefaultClientWebSocketSession.send(op: Int, data: JsonElement) =
        send(Frame.Text(Discord.json.encodeToString(GatewayPayload(op = op, d = data))))

    private enum class SessionEnd { Reconnect, AuthFailed }

    private class ZombieConnectionException : IllegalStateException("Discord gateway stopped acknowledging heartbeats")

    private class ZlibStream {
        private val inflater = Inflater()
        private val pending = ByteArrayOutputStream()
        private val chunk = ByteArray(CHUNK_SIZE)

        fun textOf(frame: Frame) = when (frame) {
            is Frame.Binary -> feed(frame.readBytes())
            is Frame.Text -> frame.readText()
            else -> null
        }

        private fun feed(bytes: ByteArray): String? {
            pending.write(bytes)
            val data = pending.toByteArray()
            if (data.size < ZLIB_SUFFIX.size || !data.endsWith(ZLIB_SUFFIX)) return null
            pending.reset()

            inflater.setInput(data)
            val out = ByteArrayOutputStream()
            while (true) {
                val count = inflater.inflate(chunk)
                if (count > 0) out.write(chunk, 0, count)
                else if (inflater.needsInput() || inflater.finished() || inflater.needsDictionary()) break
            }
            return out.toString(Charsets.UTF_8.name())
        }

        fun close() = inflater.end()

        private fun ByteArray.endsWith(suffix: ByteArray) =
            suffix.indices.all { this[size - suffix.size + it] == suffix[it] }

        private companion object {
            const val CHUNK_SIZE = 16 * 1024
            val ZLIB_SUFFIX = byteArrayOf(0, 0, 0xFF.toByte(), 0xFF.toByte())
        }
    }

    private companion object {
        const val GATEWAY_URL = "wss://gateway.discord.gg/?v=10&encoding=json&compress=zlib-stream"

        const val OP_DISPATCH = 0
        const val OP_HEARTBEAT = 1
        const val OP_IDENTIFY = 2
        const val OP_PRESENCE_UPDATE = 3
        const val OP_RECONNECT = 7
        const val OP_INVALID_SESSION = 9
        const val OP_HELLO = 10
        const val OP_HEARTBEAT_ACK = 11

        const val DEFAULT_HEARTBEAT_MS = 41_250L
        val AUTH_FAILED_CODES = setOf<Short>(4004)

        val CLOSE_TIMEOUT = 3.seconds
        val MIN_BACKOFF = 5.seconds
        val MAX_BACKOFF = 10.minutes
    }
}
