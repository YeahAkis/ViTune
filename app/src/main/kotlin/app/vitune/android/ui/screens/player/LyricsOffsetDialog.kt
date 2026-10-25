package app.vitune.android.ui.screens.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.vitune.android.R
import app.vitune.android.ui.components.themed.DefaultDialog
import app.vitune.android.ui.components.themed.DialogTextButton
import app.vitune.android.ui.components.themed.SecondaryTextButton
import app.vitune.android.ui.components.themed.Slider
import app.vitune.android.ui.components.themed.TextField
import app.vitune.android.utils.center
import app.vitune.android.utils.medium
import app.vitune.android.utils.semiBold
import app.vitune.core.ui.LocalAppearance
import java.util.Locale
import kotlin.math.roundToLong

private const val SLIDER_RANGE_SECONDS = 30f
private const val SLIDER_STEP_SECONDS = 0.5f
private const val MAX_OFFSET_SECONDS = 600f
private const val FINE_STEP_SECONDS = 0.5f

@Composable
fun LyricsOffsetDialog(
    offsetMillis: Long,
    currentPositionMillis: () -> Long,
    onOffsetChange: (Long) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) = DefaultDialog(
    onDismiss = onDismiss,
    modifier = modifier
) {
    val (_, typography) = LocalAppearance.current
    val focusManager = LocalFocusManager.current

    var seconds by remember { mutableFloatStateOf(offsetMillis / 1000f) }
    var input by remember { mutableStateOf(seconds.toInput()) }

    fun commit(value: Float) {
        seconds = value.coerceIn(-MAX_OFFSET_SECONDS, MAX_OFFSET_SECONDS)
        input = seconds.toInput()
        onOffsetChange((seconds * 1000).roundToLong())
    }

    BasicText(
        text = stringResource(R.string.lyrics_offset),
        style = typography.s.semiBold,
        modifier = Modifier.padding(vertical = 8.dp)
    )

    BasicText(
        text = stringResource(R.string.lyrics_offset_description),
        style = typography.xs.medium.center,
        modifier = Modifier.padding(bottom = 8.dp)
    )

    Slider(
        state = seconds.coerceIn(-SLIDER_RANGE_SECONDS, SLIDER_RANGE_SECONDS),
        setState = {
            seconds = it
            input = it.toInput()
        },
        onSlideComplete = { commit(seconds) },
        range = -SLIDER_RANGE_SECONDS..SLIDER_RANGE_SECONDS,
        steps = (SLIDER_RANGE_SECONDS * 2 / SLIDER_STEP_SECONDS).toInt() - 1,
        modifier = Modifier
            .height(36.dp)
            .fillMaxWidth()
    )

    Row(
        horizontalArrangement = Arrangement.SpaceEvenly,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
    ) {
        SecondaryTextButton(
            text = stringResource(R.string.format_lyrics_offset_step, -FINE_STEP_SECONDS),
            onClick = { commit(seconds - FINE_STEP_SECONDS) }
        )

        TextField(
            value = input,
            onValueChange = { input = it },
            textStyle = typography.s.semiBold.center,
            singleLine = true,
            hintText = stringResource(R.string.lyrics_offset_hint),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Decimal,
                imeAction = ImeAction.Done
            ),
            keyboardActions = KeyboardActions(
                onDone = {
                    input.replace(',', '.').toFloatOrNull()?.let(::commit) ?: run { input = seconds.toInput() }
                    focusManager.clearFocus()
                }
            ),
            modifier = Modifier.weight(1f)
        )

        SecondaryTextButton(
            text = stringResource(R.string.format_lyrics_offset_step, FINE_STEP_SECONDS),
            onClick = { commit(seconds + FINE_STEP_SECONDS) }
        )
    }

    Row(
        horizontalArrangement = Arrangement.SpaceEvenly,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
    ) {
        SecondaryTextButton(
            text = stringResource(R.string.lyrics_offset_use_current_position),
            onClick = { commit(currentPositionMillis() / 1000f) }
        )

        SecondaryTextButton(
            text = stringResource(R.string.reset),
            onClick = { commit(0f) }
        )
    }

    DialogTextButton(
        text = stringResource(R.string.done),
        primary = true,
        onClick = {
            input.replace(',', '.').toFloatOrNull()?.takeIf { it != seconds }?.let(::commit)
            onDismiss()
        }
    )
}

private fun Float.toInput() = String.format(Locale.ROOT, "%.1f", this)
