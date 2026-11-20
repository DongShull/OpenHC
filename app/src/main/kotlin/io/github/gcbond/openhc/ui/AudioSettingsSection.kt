package io.github.gcbond.openhc.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.gcbond.openhc.R
import io.github.gcbond.openhc.settings.AudioPreferences
import io.github.gcbond.openhc.settings.PcmPhoneRecord
import java.text.DateFormat
import java.util.Date

/**
 * 设置页「声音（实验）」一组：总开关、声音延后、车机麦克风（含录音权限申请）、已自动改用 AAC 的手机（可以恢复）；
 * 另一组「声音调试」是几个实验用的开关（编码、传输方式、多声道分流、低延迟输出、麦克风能力声明）。改动在下次连接时生效。
 */
@Composable
internal fun AudioSettingsSection() {
    val context = LocalContext.current
    val prefs = remember(context) { AudioPreferences(context) }
    var enabled by remember { mutableStateOf(prefs.enabled) }
    var delay by remember { mutableIntStateOf(prefs.delayMillis) }
    var fallbacks by remember { mutableStateOf(prefs.pcmFallbacks().records.filter { it.useAac }) }

    SettingsGroup(stringResource(R.string.settings_audio)) {
        SwitchRow(
            title = stringResource(R.string.settings_audio_enabled),
            checked = enabled,
            description = stringResource(R.string.settings_audio_hint),
        ) {
            enabled = it
            prefs.enabled = it
        }
        val delays = AudioPreferences.DELAY_CHOICES
        ChoiceRow(
            title = stringResource(R.string.settings_audio_delay),
            options = delays.map {
                if (it == 0) stringResource(R.string.settings_audio_delay_min) else stringResource(R.string.settings_audio_delay_plus, it)
            },
            selected = delays.indexOf(delay),
            description = stringResource(R.string.settings_audio_delay_hint),
        ) { index ->
            delay = delays[index]
            prefs.delayMillis = delays[index]
        }
        GroupRow {
            RowTitle(stringResource(R.string.settings_audio_fallback_title))
            if (fallbacks.isEmpty()) {
                RowDescription(stringResource(R.string.settings_audio_fallback_empty), Modifier.padding(top = 2.dp))
            } else {
                for (record in fallbacks) {
                    FallbackRow(record) {
                        prefs.forgetPcmFallback(record.key)
                        fallbacks = prefs.pcmFallbacks().records.filter { it.useAac }
                    }
                }
                RowDescription(stringResource(R.string.settings_audio_fallback_hint), Modifier.padding(top = 6.dp))
            }
        }
    }
}

/** 一台改用 AAC 的手机：名字、编号前 6 位、原因、记下的日期，右边「恢复 PCM」。 */
@Composable
private fun FallbackRow(record: PcmPhoneRecord, onReset: () -> Unit) {
    val name = record.name ?: stringResource(R.string.settings_audio_fallback_unnamed)
    val date = DateFormat.getDateInstance(DateFormat.SHORT).format(Date(record.updatedAtMillis))
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RowDescription(
            stringResource(R.string.settings_audio_fallback_item, name, record.key.take(6), record.lastFailure ?: "", date),
            Modifier.weight(1f),
            color = Palette.Text,
        )
        Spacer(Modifier.width(12.dp))
        ActionButton(stringResource(R.string.settings_audio_fallback_reset), onClick = onReset)
    }
}
