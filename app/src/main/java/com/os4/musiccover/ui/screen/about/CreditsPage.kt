package com.os4.musiccover.ui.screen.about

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.os4.musiccover.R
import com.os4.musiccover.ui.util.PageScaffold
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.preference.ArrowPreference

/**
 * Who this module borrowed from.
 *
 * Every project here is one whose code, design or data is in the build - not a list of things
 * that were admired from a distance. The summary says what was taken, because "thanks to X" with
 * nothing after it tells a reader nothing and cannot be checked against the source.
 *
 * Names are not translated: they are what their authors call them, in both languages. Only the
 * line saying what was taken is a string resource.
 *
 * Every row opens the project it names, so every entry has to have somewhere to go.
 */
private data class Credit(
    val name: String,
    val summary: String,
    val url: String,
)

@Composable
fun CreditsPageContent(
    onBack: () -> Unit,
    isBlurEnabled: Boolean = true,
) {
    val uriHandler = LocalUriHandler.current

    val uiCredits = listOf(
        Credit(
            "HyperNavBar",
            stringResource(R.string.credits_hypernavbar),
            "https://github.com/HyperNavBar/HyperNavBar",
        ),
        Credit(
            "miuix",
            stringResource(R.string.credits_miuix),
            "https://github.com/compose-miuix-ui/miuix",
        ),
        Credit(
            "AndroidLiquidGlass",
            stringResource(R.string.credits_liquid_glass),
            "https://github.com/Kyant0/AndroidLiquidGlass",
        ),
        Credit(
            "github-markdown-css",
            stringResource(R.string.credits_markdown_css),
            "https://github.com/sindresorhus/github-markdown-css",
        ),
    )

    val lyricCredits = listOf(
        Credit(
            "@CialloUM",
            stringResource(R.string.credits_cialloum),
            "https://www.coolapk.com/u/37608778",
        ),
        Credit(
            "@Leaf-lsgtky",
            stringResource(R.string.credits_leaf),
            "https://github.com/Leaf-lsgtky",
        ),
        Credit(
            "HyperChanger",
            stringResource(R.string.credits_hyperchanger),
            "https://github.com/ColdP/HyperChanger",
        ),
        Credit(
            "HyperTweak",
            stringResource(R.string.credits_hypertweak),
            "https://github.com/TakeKazeX/HyperTweak",
        ),
        Credit(
            "AMLL",
            stringResource(R.string.credits_amll),
            "https://github.com/amll-dev/applemusic-like-lyrics",
        ),
        Credit(
            "AMLL TTML DB",
            stringResource(R.string.credits_amll_db),
            "https://github.com/amll-dev/amll-ttml-db",
        ),
        Credit(
            "Accompanist Lyrics",
            stringResource(R.string.credits_accompanist),
            "https://github.com/6xingyv/accompanist-lyrics-core",
        ),
        Credit(
            "LyricInfo",
            stringResource(R.string.credits_lyricinfo),
            "https://github.com/limczhh/LyricInfo",
        ),
        Credit(
            "HyperLyrics Enhanced",
            stringResource(R.string.credits_hle),
            "https://github.com/juren233/HyperLyrics-Enhanced",
        ),
    )

    val toolCredits = listOf(
        // The fork, not the original: it is the one this module actually read.
        Credit(
            "InstallerX Revived",
            stringResource(R.string.credits_installerx),
            "https://github.com/wxxsfxyzm/InstallerX-Revived",
        ),
        Credit(
            "LSPosed",
            stringResource(R.string.credits_lsposed),
            "https://github.com/LSPosed/LSPosed",
        ),
    )

    // This group leads, so the first name on the page is InstallerX Revived.
    val groups = remember(uiCredits, lyricCredits, toolCredits) {
        listOf(
            R.string.credits_group_tools to toolCredits,
            R.string.credits_group_ui to uiCredits,
            R.string.credits_group_lyrics to lyricCredits,
        )
    }

    PageScaffold(
        title = stringResource(R.string.about_credits),
        isBlurEnabled = isBlurEnabled,
        onBack = onBack,
    ) {
        item {
            Card(
                modifier = Modifier
                    .padding(horizontal = 12.dp)
                    .padding(top = 12.dp)
            ) {
                BasicComponent(summary = stringResource(R.string.credits_intro))
            }
        }

        groups.forEach { (heading, credits) ->
            item {
                SmallTitle(
                    text = stringResource(heading),
                    modifier = Modifier.padding(top = 12.dp),
                )
                Card(modifier = Modifier.padding(horizontal = 12.dp)) {
                    credits.forEach { credit ->
                        ArrowPreference(
                            title = credit.name,
                            summary = credit.summary,
                            onClick = { uriHandler.openUri(credit.url) },
                        )
                    }
                }
            }
        }

        item { Spacer(Modifier.height(12.dp)) }
    }
}
