package com.os4.musiccover.ui.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.os4.musiccover.R
import com.os4.musiccover.app.SettingsStore
import top.yukonga.miuix.kmp.basic.Text as MiuixText
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * What a settings page says about the app's own copy (stage 5.3): that the module is away, that
 * this user cannot reach it, or that a batch went unconfirmed. One line each, above the rows
 * they are about rather than instead of them - every control stays usable, because the local
 * copy is the truth and the sync catches up.
 */
@Composable
fun SettingsNotices(alive: Boolean, modifier: Modifier = Modifier) {
    val migrated by SettingsStore.migratedState.collectAsState()
    val unconfirmed by SettingsStore.unconfirmed.collectAsState()
    if (!migrated) return
    val lines = buildList {
        if (SettingsStore.isThirdPartyUser()) {
            add(stringResource(R.string.settings_notice_other_user))
        } else if (!alive) {
            add(stringResource(R.string.settings_notice_offline))
        }
        if (unconfirmed > 0) add(stringResource(R.string.settings_notice_unconfirmed))
    }
    if (lines.isEmpty()) return
    Column(modifier = modifier.padding(start = 28.dp, end = 28.dp, top = 8.dp, bottom = 8.dp)) {
        for (line in lines) {
            MiuixText(
                text = line,
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
    }
}
