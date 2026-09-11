package com.os4.musiccover.ui.screen.about

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.os4.musiccover.R
import com.os4.musiccover.ui.util.openQqGroup
import com.os4.musiccover.ui.util.openTelegramGroup
import com.os4.musiccover.updater.InstallOutcome
import com.os4.musiccover.updater.UpdateApi
import com.os4.musiccover.updater.UpdateCheck
import com.os4.musiccover.ui.component.markdown.MarkdownContent
import com.os4.musiccover.updater.UpdateInfo
import com.os4.musiccover.updater.UpdateInstaller
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator
import top.yukonga.miuix.kmp.basic.Text as MiuixText
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.LocalDismissState
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme
import top.yukonga.miuix.kmp.window.WindowDialog

private const val POLL_MS = 200L

/** Where the links dialog sends people. The QQ pair is the group's number and its share link. */
private const val RELEASES_PAGE = "https://github.com/zyl6932/HyperMusicCover/releases"
private const val TELEGRAM_GROUP = "https://t.me/HyperMusicCover"
private const val QQ_GROUP_UIN = "392493127"
private const val QQ_GROUP_LINK = "https://qm.qq.com/q/RcLbYXgBy2"

/**
 * Every piece of state the updater needs.
 *
 * This app has no ViewModel and this is not the place to introduce one, but the update state does
 * not belong to the page's content composable either: the dialogs render outside the Scaffold,
 * the install runs in a process-scoped coroutine, and its verdict arrives on a broadcast
 * receiver. One holder beats threading eight parameters through two composables.
 */
@Stable
internal class UpdateStates {
    var update by mutableStateOf<UpdateInfo?>(null)
    var installing by mutableStateOf(false)
    /** What the last attempt has to say, shown in a dialog. Null while there is nothing to say. */
    var status by mutableStateOf<String?>(null)
    var notes by mutableStateOf(false)
    var links by mutableStateOf(false)
}

/** What the page reads, and the three things it can ask for. */
@Stable
class UpdateController internal constructor(internal val states: UpdateStates) {

    val update: UpdateInfo? get() = states.update
    val installing: Boolean get() = states.installing
    val status: String? get() = states.status

    val showNotes: () -> Unit = { states.notes = true }
    val showLinks: () -> Unit = { states.links = true }

    /**
     * Download the release being offered and hand it to the system installer.
     *
     * Nothing here reports through a Toast. Toasts are silently dropped for any app whose
     * notifications are off, and this app never asks for `POST_NOTIFICATIONS`, so a Toast-drawn
     * failure looks exactly like nothing happening - which is how the first version of this
     * behaved on the device it was built for. Every message below lands in [states] instead, and
     * the About page renders it itself.
     */
    fun directUpdate(context: Context) {
        val target = states.update ?: return
        if (states.installing) return
        states.installing = true
        states.status = null
        UpdateInstaller.start(
            context,
            UpdateApi.orderedCandidates(context, target.apkUrl),
            "HyperMusicCover-${target.versionName}.apk",
        )
    }
}

/**
 * Builds the controller and runs everything that has to happen on its behalf.
 *
 * The effects live here rather than in the page because they belong to the updater's lifetime,
 * not the page's: the check is cached across visits, the recovery answer is read once per process,
 * and the install outlives the composition that started it.
 */
@Composable
fun rememberUpdateController(refreshKey: Int, checkUpdate: Boolean): UpdateController {
    val context = LocalContext.current
    // LocalResources, not context.getString: the latter is not configuration-aware, and lint
    // fails the build on it (LocalContextGetResourceValueCall).
    val resources = LocalResources.current
    // Two separate questions, asked together: may this build update itself at all (not a fork,
    // not a debug build), and does the user want it to. Flipping the setting re-runs every effect
    // below, which is what makes the switch on the Settings page take effect immediately.
    val allowed = remember(checkUpdate) { checkUpdate && UpdateApi.enabled(context) }
    val states = remember {
        UpdateStates().apply {
            update = UpdateCheck.result
            installing = UpdateInstaller.inFlight
        }
    }

    // The app already checked when it started; this picks that answer up, and re-asks only if it
    // has since gone stale - opening the page is the moment a stale answer is worth refreshing,
    // and the interval above is what keeps that from being a request per visit.
    LaunchedEffect(refreshKey, allowed) {
        states.update = UpdateCheck.result
        if (!allowed) return@LaunchedEffect
        UpdateCheck.refresh(context)
        states.update = UpdateCheck.result
    }

    // The install itself is the installer app's business; all this waits for is the moment the
    // APK has been handed over, or the reason it could not be. There is no deadline any more -
    // nothing here depends on a confirmation screen coming back.
    LaunchedEffect(states.installing, allowed) {
        if (!states.installing || !allowed) return@LaunchedEffect
        while (true) {
            when (val outcome = UpdateInstaller.lastOutcome) {
                is InstallOutcome.NoInstaller -> {
                    states.installing = false
                    states.status = resources.getString(R.string.update_no_installer)
                    return@LaunchedEffect
                }

                is InstallOutcome.Failed -> {
                    states.installing = false
                    states.status = resources.getString(R.string.update_failed, outcome.message)
                    return@LaunchedEffect
                }

                is InstallOutcome.HandedOff -> {
                    states.installing = false
                    return@LaunchedEffect
                }

                null -> Unit
            }
            if (!UpdateInstaller.inFlight) {
                states.installing = false
                return@LaunchedEffect
            }
            delay(POLL_MS)
        }
    }

    return remember { UpdateController(states) }
}

/**
 * `有可用更新：x.y.z`, and it is a button.
 *
 * InstallerX's About page has this same line as plain text, because it has nowhere to take a
 * tap: its release JSON never deserializes the notes and its update dialog is a pair of links out
 * to GitHub. Here the line is the way into the release notes, so the one thing a person wants
 * after reading "there is a new version" - what changed - is one tap away.
 */
@Composable
fun UpdateHint(modifier: Modifier = Modifier, update: UpdateInfo?, onShowNotes: () -> Unit) {
    if (update == null) return
    MiuixText(
        // The touch target is the whole width, but the press feedback is off: clickable would
        // otherwise paint its indication across that full width, which on a line of text reads as
        // a band of shadow appearing under the finger rather than as the text being pressed.
        modifier = modifier
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onShowNotes,
            )
            .padding(vertical = 8.dp),
        color = colorScheme.primary,
        text = stringResource(R.string.update_available, update.versionName),
        fontSize = 14.sp,
        textAlign = TextAlign.Center,
    )
}

/** The two rows InstallerX has. */
@Composable
fun UpdateRows(
    update: UpdateInfo?,
    installing: Boolean,
    onGetUpdate: () -> Unit,
    onDirectUpdate: () -> Unit,
) {
    ArrowPreference(
        title = stringResource(R.string.get_update),
        summary = stringResource(R.string.get_update_detail),
        onClick = onGetUpdate,
    )
    if (update != null) {
        ArrowPreference(
            title = stringResource(R.string.get_update_directly),
            summary = stringResource(
                if (installing) R.string.updating else R.string.get_update_directly_desc
            ),
            onClick = onDirectUpdate,
        )
    }
}

/**
 * All four dialogs, rendered together after the page's Scaffold.
 *
 * They open their own windows, so they must not be nested inside the page's content - and three
 * independent flags drive them rather than one, because they are three different things: what
 * changed, where else to get it, and which mirror to fetch through.
 */
@Composable
fun UpdateDialogs(controller: UpdateController) {
    val states = controller.states
    val context = LocalContext.current
    UpdateNotesDialog(
        show = states.notes,
        update = controller.update,
        onDismiss = { states.notes = false },
        onConfirm = {
            states.notes = false
            controller.directUpdate(context)
        },
    )
    UpdateLinksDialog(states.links) { states.links = false }
    UpdateStatusDialog(controller.status) { states.status = null }
}

/** Everything the install has to say, shown in the page rather than in a Toast. */
@Composable
private fun UpdateStatusDialog(status: String?, onDismiss: () -> Unit) {
    if (status == null) return
    WindowDialog(
        show = true,
        title = stringResource(R.string.update_status_title),
        onDismissRequest = onDismiss,
    ) {
        val dismiss = LocalDismissState.current
        Column {
            MiuixText(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 320.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = 8.dp),
                text = status,
                fontSize = 14.sp,
                color = colorScheme.onSurface,
            )
            TextButton(
                modifier = Modifier.fillMaxWidth(),
                text = stringResource(R.string.update_close),
                onClick = { dismiss?.invoke() },
            )
        }
    }
}

/**
 * What changed in the release being offered, and the way into it.
 *
 * KernelSU's shape, which is what this follows: the changelog is the body of the dialog and the
 * confirm button is the update itself, so reading what is about to be installed and agreeing to
 * it are one step rather than two.
 */
@Composable
private fun UpdateNotesDialog(
    show: Boolean,
    update: UpdateInfo?,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    if (update == null) return
    WindowDialog(
        show = show,
        title = stringResource(R.string.update_notes_title),
        summary = update.versionName,
        onDismissRequest = onDismiss,
    ) {
        // The documented way out of a WindowDialog from inside its own content; the buttons do
        // not need the callback threaded in to reach it.
        val dismiss = LocalDismissState.current
        Column {
            if (update.notes.isBlank()) {
                MiuixText(
                    modifier = Modifier.padding(bottom = 8.dp),
                    text = stringResource(R.string.update_notes_empty),
                    fontSize = 14.sp,
                    color = colorScheme.onSurface,
                )
            } else {
                MarkdownContent(
                    modifier = Modifier.padding(bottom = 8.dp),
                    content = update.notes,
                )
            }
            // The same row KernelSU lays out: the two buttons share the width, the confirm one
            // carries the primary colour, and a 20dp spacer keeps them off each other.
            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.padding(top = 12.dp),
            ) {
                TextButton(
                    modifier = Modifier.weight(1f),
                    text = stringResource(R.string.update_cancel),
                    onClick = { dismiss?.invoke() },
                )
                Spacer(Modifier.width(20.dp))
                TextButton(
                    modifier = Modifier.weight(1f),
                    text = stringResource(R.string.update_confirm),
                    onClick = onConfirm,
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                )
            }
        }
    }
}

/** Where to get it by hand, kept as InstallerX has it: links out, and nothing about this build. */
@Composable
private fun UpdateLinksDialog(show: Boolean, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    WindowDialog(
        show = show,
        title = stringResource(R.string.get_update),
        onDismissRequest = onDismiss,
    ) {
        val dismiss = LocalDismissState.current
        Column {
            Card(modifier = Modifier.padding(bottom = 8.dp)) {
                BasicComponent(
                    title = "GitHub",
                    onClick = {
                        uriHandler.openUri(RELEASES_PAGE)
                        dismiss?.invoke()
                    },
                )
                BasicComponent(
                    title = stringResource(R.string.about_telegram),
                    onClick = {
                        if (!context.openTelegramGroup(TELEGRAM_GROUP)) {
                            uriHandler.openUri(TELEGRAM_GROUP)
                        }
                        dismiss?.invoke()
                    },
                )
                BasicComponent(
                    title = stringResource(R.string.about_qq_group),
                    onClick = {
                        if (!context.openQqGroup(QQ_GROUP_UIN)) {
                            uriHandler.openUri(QQ_GROUP_LINK)
                        }
                        dismiss?.invoke()
                    },
                )
            }
            TextButton(
                modifier = Modifier.fillMaxWidth(),
                text = stringResource(R.string.update_cancel),
                onClick = { dismiss?.invoke() },
            )
        }
    }
}
