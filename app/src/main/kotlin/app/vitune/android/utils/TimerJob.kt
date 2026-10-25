package app.vitune.android.utils

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

interface TimerJob {
    val millisLeft: StateFlow<Long?>
    val endsAtMillis: Long
    val totalMillis: Long
    fun add(millis: Long)
    fun cancel()
}

fun CoroutineScope.timer(
    delayMillis: Long,
    minMillisLeft: Long = 0L,
    maxMillisLeft: Long = Long.MAX_VALUE,
    fastTickWindowMillis: () -> Long = { 0L },
    onChange: (TimerJob) -> Unit = { },
    onTick: (millisLeft: Long) -> Unit = { },
    onCompletion: () -> Unit
): TimerJob {
    val end = AtomicLong(System.currentTimeMillis() + delayMillis)
    val total = AtomicLong(delayMillis)
    val millisLeft = MutableStateFlow<Long?>(delayMillis)

    val job = launch {
        while (isActive) {
            val left = end.get() - System.currentTimeMillis()
            if (left <= 0) break

            millisLeft.value = left
            onTick(left)

            val window = fastTickWindowMillis()
            delay(
                when {
                    left <= window -> FAST_TICK_MILLIS
                    else -> (left - window).coerceIn(FAST_TICK_MILLIS, TICK_MILLIS)
                }
            )
        }
        millisLeft.value = null
    }
    val disposableHandle = job.invokeOnCompletion { if (it == null) onCompletion() }

    return object : TimerJob {
        override val millisLeft get() = millisLeft.asStateFlow()
        override val endsAtMillis get() = end.get()
        override val totalMillis get() = total.get()

        override fun add(millis: Long) {
            val now = System.currentTimeMillis()
            val left = (end.get() - now + millis).coerceIn(minMillisLeft, maxMillisLeft)
            end.set(now + left)
            total.updateAndGet { maxOf(it, left) }
            millisLeft.value = left
            onChange(this)
        }

        override fun cancel() {
            millisLeft.value = null
            disposableHandle.dispose()
            job.cancel()
        }
    }.also(onChange)
}

private const val TICK_MILLIS = 1_000L
private const val FAST_TICK_MILLIS = 100L
