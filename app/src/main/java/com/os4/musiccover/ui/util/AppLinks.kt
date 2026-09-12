/*
 * Adapted from HyperNavBar (https://github.com/HyperNavBar/HyperNavBar),
 * licensed under the Apache License, Version 2.0.
 *
 * Changes in HyperMusicCover: package renamed, project links and strings replaced,
 * and the pieces this project does not use removed.
 */
package com.os4.musiccover.ui.util

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri

/**
 * The About page's chat links, aimed at the app that owns them instead of at a browser.
 *
 * Neither service claims the https form of its own invite link: https://t.me/x lands on the
 * system chooser and https://qm.qq.com/x on Edge/Chrome, which is the browser-then-redirect
 * detour this file exists to skip. Telegram answers tg:// instead and QQ answers mqqapi - and
 * the qm.qq.com link is a dead end even with QQ installed, since its JumpActivity routes that
 * host to QQ's home screen rather than to the group. So each link is rewritten into the form
 * its app actually answers.
 *
 * Every launch is preceded by resolveActivity: an intent nothing can take would throw
 * ActivityNotFoundException out of a click handler, and a silent no-op is the better failure
 * for a link row. The caller falls back to the https URL when one of these returns false.
 */

/** QQ, TIM and QQ International - three packages behind the same JumpActivity. */
private val QQ_PACKAGES = listOf(
    "com.tencent.mobileqq",
    "com.tencent.tim",
    "com.tencent.mobileqqi",
)

/**
 * Opens a QQ group's join card, returning false when no QQ-family app is installed.
 *
 * [groupUin] is the group number, which is what the card route takes. The intent names the
 * package rather than being left to the system because mqqapi is claimed by TIM and the other
 * clients too; the card route is the shape QQ's own group QR codes encode.
 */
fun Context.openQqGroup(groupUin: String): Boolean {
    val card = Uri.parse(
        "mqqapi://card/show_pslcard" +
            "?src_type=internal&version=1&uin=$groupUin&card_type=group&source=qrcode"
    )
    for (pkg in QQ_PACKAGES) {
        val aimed = Intent(Intent.ACTION_VIEW, card).setPackage(pkg)
        if (packageManager.resolveActivity(aimed, PackageManager.MATCH_DEFAULT_ONLY) == null) continue
        startActivity(aimed)
        return true
    }
    return false
}

/**
 * Opens a Telegram group invite link, returning false when no Telegram client is installed.
 *
 * tg://resolve?domain= is answered by every client, forks and third-party ones included, so no
 * package is named; the domain is the last segment of the t.me link the page displays.
 */
fun Context.openTelegramGroup(url: String): Boolean {
    val domain = url.trimEnd('/').substringAfterLast('/')
    return startIfHandled(Intent(Intent.ACTION_VIEW, Uri.parse("tg://resolve?domain=$domain")))
}

private fun Context.startIfHandled(intent: Intent): Boolean {
    if (packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY) == null) return false
    startActivity(intent)
    return true
}
