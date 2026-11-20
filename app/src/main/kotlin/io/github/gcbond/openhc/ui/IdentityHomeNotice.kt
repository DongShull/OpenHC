package io.github.gcbond.openhc.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.gcbond.openhc.IdentityLoader
import io.github.gcbond.openhc.R
import io.github.gcbond.openhc.connection.ConnectionUiState
import io.github.gcbond.openhc.connection.Notice

/**
 * 首页顶上的兜底提示：安装包里的身份参数缺少或不能用（例如有人把仓库里的文件改坏了）时一张醒目的卡片，
 * 正常时不显示。身份参数固定在仓库里，软件里没有地方填写。
 */
@Composable
internal fun IdentityHomeNotice(identity: IdentityLoader.Result, modifier: Modifier = Modifier) {
    val text = when (identity) {
        is IdentityLoader.Result.Loaded -> return
        IdentityLoader.Result.Missing -> stringResource(R.string.home_identity_missing)
        is IdentityLoader.Result.Invalid -> stringResource(R.string.home_identity_invalid, identity.reason)
    }
    CardBox(modifier.fillMaxWidth(), color = Palette.Warn.copy(alpha = PROMPT_TINT)) {
        Column {
            Text(stringResource(R.string.home_identity_title), style = UiType.status, color = Palette.Warn)
            Text(text, style = UiType.hint, color = Palette.Text, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

/** 页脚里的一行：车型编号；缺少或不能用时为 null（顶上有提示卡片）。 */
@Composable
internal fun identityFooterLine(identity: IdentityLoader.Result): String? {
    val loaded = identity as? IdentityLoader.Result.Loaded ?: return null
    return stringResource(R.string.home_identity_model, loaded.assets.host.fullModel)
}

/** 提示卡片的底色：提醒色叠一层淡的。 */
private const val PROMPT_TINT = 0.16f

/**
 * 身份参数的状态由 [IdentityHomeNotice] 按当前结果显示，状态卡里不再重复。
 */
internal fun ConnectionUiState.withoutIdentityNotice(): ConnectionUiState =
    if (notice == Notice.IdentityMissing || notice is Notice.IdentityInvalid) copy(notice = null) else this
