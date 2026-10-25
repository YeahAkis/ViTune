package app.vitune.android.ui.components.themed

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.vitune.android.LocalPlayerServiceBinder
import app.vitune.android.R
import app.vitune.android.preferences.PlayerPreferences
import app.vitune.android.service.PlayerService
import app.vitune.android.utils.center
import app.vitune.android.utils.formatAsDuration
import app.vitune.android.utils.semiBold
import app.vitune.core.ui.LocalAppearance
import app.vitune.core.ui.utils.roundedShape
import kotlinx.coroutines.flow.MutableStateFlow

private const val MINUTE_MILLIS = 60_000L
private const val MIN_MINUTES = (PlayerService.SLEEP_TIMER_MIN_MILLIS / MINUTE_MILLIS).toInt()
private const val MAX_MINUTES = (PlayerService.SLEEP_TIMER_MAX_MILLIS / MINUTE_MILLIS).toInt()
private val STEPS = listOf(1, 5, 10, 30)

@Composable
fun SleepTimerDialog(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) = DefaultDialog(
    onDismiss = onDismiss,
    modifier = modifier
) {
    val (_, typography) = LocalAppearance.current
    val binder = LocalPlayerServiceBinder.current

    val millisLeftFlow = binder?.sleepTimerMillisLeft
    val millisLeft by remember(millisLeftFlow) { millisLeftFlow ?: MutableStateFlow(null) }.collectAsState()
    val isRunning = millisLeft != null

    var minutes by rememberSaveable {
        mutableIntStateOf(PlayerPreferences.sleepTimerLastMinutes.coerceIn(MIN_MINUTES, MAX_MINUTES))
    }

    fun canChange(delta: Int) = millisLeft?.let {
        if (delta < 0) it + delta * MINUTE_MILLIS >= PlayerService.SLEEP_TIMER_MIN_MILLIS
        else it + delta * MINUTE_MILLIS <= PlayerService.SLEEP_TIMER_MAX_MILLIS
    } ?: ((minutes + delta) in MIN_MINUTES..MAX_MINUTES)

    fun change(delta: Int) {
        if (isRunning) binder?.addToSleepTimer(delta * MINUTE_MILLIS)
        else minutes = (minutes + delta).coerceIn(MIN_MINUTES, MAX_MINUTES)
    }

    BasicText(
        text = stringResource(if (isRunning) R.string.sleep_timer else R.string.set_sleep_timer),
        style = typography.s.semiBold,
        modifier = Modifier.padding(vertical = 8.dp)
    )

    BasicText(
        text = millisLeft?.let { formatAsDuration(it) } ?: listOfNotNull(
            (minutes / 60).takeIf { it > 0 }?.let { stringResource(R.string.format_hours, it) },
            (minutes % 60).takeIf { it > 0 || minutes < 60 }?.let { stringResource(R.string.format_minutes, it) }
        ).joinToString(" "),
        style = typography.xl.semiBold.center,
        modifier = Modifier.padding(vertical = 12.dp)
    )

    StepRow(steps = STEPS.reversed().map { -it }, canChange = ::canChange, onChange = ::change)
    StepRow(steps = STEPS, canChange = ::canChange, onChange = ::change)

    SecondaryTextButton(
        text = stringResource(R.string.sleep_timer_until_song_end),
        onClick = {
            binder?.startSleepTimerUntilSongEnd()
            onDismiss()
        },
        modifier = Modifier.padding(vertical = 12.dp)
    )

    Row(
        horizontalArrangement = Arrangement.SpaceEvenly,
        modifier = Modifier.fillMaxWidth()
    ) {
        if (isRunning) {
            DialogTextButton(
                text = stringResource(R.string.stop),
                onClick = {
                    binder?.cancelSleepTimer()
                    onDismiss()
                }
            )

            DialogTextButton(
                text = stringResource(R.string.done),
                primary = true,
                onClick = onDismiss
            )
        } else {
            DialogTextButton(
                text = stringResource(R.string.cancel),
                onClick = onDismiss
            )

            DialogTextButton(
                text = stringResource(R.string.set),
                primary = true,
                onClick = {
                    PlayerPreferences.sleepTimerLastMinutes = minutes
                    binder?.startSleepTimer(minutes * MINUTE_MILLIS)
                    onDismiss()
                }
            )
        }
    }
}

@Composable
private fun StepRow(
    steps: List<Int>,
    canChange: (Int) -> Boolean,
    onChange: (Int) -> Unit
) = Row(
    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
    modifier = Modifier
        .fillMaxWidth()
        .padding(vertical = 4.dp)
) {
    val (colorPalette, typography) = LocalAppearance.current

    steps.forEach { delta ->
        val enabled = canChange(delta)
        val description = if (delta < 0) stringResource(R.string.format_sleep_timer_subtract_description, -delta)
        else stringResource(R.string.format_sleep_timer_add_description, delta)

        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .weight(1f)
                .alpha(if (enabled) 1f else 0.5f)
                .clip(16.dp.roundedShape)
                .background(colorPalette.background0)
                .clickable(enabled = enabled) { onChange(delta) }
                .semantics { contentDescription = description }
                .padding(vertical = 10.dp)
        ) {
            BasicText(
                text = if (delta < 0) stringResource(R.string.format_sleep_timer_subtract_short, -delta)
                else stringResource(R.string.format_sleep_timer_add_short, delta),
                style = typography.xs.semiBold,
                maxLines = 1
            )
        }
    }
}
