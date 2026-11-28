package io.github.gcbond.openhc.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import io.github.gcbond.openhc.R
import io.github.gcbond.openhc.connection.VideoClarity
import io.github.gcbond.openhc.settings.ClarityPreferences

/**
 * 「画面」组里的「清晰度」：标准 / 高 / 最高，以可视化卡片呈现档位特点与目标码率（见 [VideoClarity]）。下次连接生效。
 */
@Composable
internal fun ClaritySettingsSection() {
    val context = LocalContext.current
    val prefs = remember(context) { ClarityPreferences(context) }
    var clarity by remember { mutableStateOf(prefs.clarity) }
    val options = VideoClarity.entries
    CardChoiceRow(
        title = stringResource(R.string.settings_clarity),
        summary = stringResource(R.string.settings_clarity_summary),
        options = options.map { clarityCardOption(it) },
        selected = options.indexOf(clarity),
        description = clarityLabel(clarity) + "。" + stringResource(R.string.settings_clarity_hint),
    ) { index ->
        clarity = options[index]
        prefs.clarity = options[index]
    }
}

@Composable
private fun clarityCardOption(clarity: VideoClarity): CardOption {
    val mbps = (clarity.bitrate ?: 0) / 1_000_000
    return when (clarity) {
        VideoClarity.STANDARD -> CardOption(
            title = stringResource(R.string.settings_clarity_standard_short),
            text = stringResource(R.string.settings_clarity_standard_card),
            tag = stringResource(R.string.settings_clarity_tag_default),
        )
        VideoClarity.HIGH -> CardOption(
            title = stringResource(R.string.settings_clarity_high_short),
            text = stringResource(R.string.settings_clarity_high_card, mbps),
            tag = stringResource(R.string.settings_clarity_tag_balanced),
            tagHighlight = true,
        )
        VideoClarity.HIGHEST -> CardOption(
            title = stringResource(R.string.settings_clarity_highest_short),
            text = stringResource(R.string.settings_clarity_highest_card, mbps),
            tag = stringResource(R.string.settings_clarity_tag_ultra),
        )
    }
}

@Composable
private fun clarityLabel(clarity: VideoClarity): String {
    val mbps = (clarity.bitrate ?: 0) / 1_000_000
    return when (clarity) {
        VideoClarity.STANDARD -> stringResource(R.string.settings_clarity_standard)
        VideoClarity.HIGH -> stringResource(R.string.settings_clarity_high, mbps)
        VideoClarity.HIGHEST -> stringResource(R.string.settings_clarity_highest, mbps)
    }
}
