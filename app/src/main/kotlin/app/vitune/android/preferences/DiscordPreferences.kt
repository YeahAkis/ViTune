package app.vitune.android.preferences

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import app.vitune.android.Dependencies
import app.vitune.android.R
import app.vitune.compose.preferences.PreferencesHolder

object DiscordPreferences : PreferencesHolder(Dependencies.application, "discord") {
    val enabledProperty = boolean(false)
    var enabled by enabledProperty
    val tokenProperty = string("")
    var token by tokenProperty
    val pauseBehaviorProperty = enum(PauseBehavior.Clear)
    var pauseBehavior by pauseBehaviorProperty

    enum class PauseBehavior(val displayName: @Composable () -> String) {
        Clear(displayName = { stringResource(R.string.discord_pause_clear) }),
        ShowPaused(displayName = { stringResource(R.string.discord_pause_show_paused) })
    }
}
