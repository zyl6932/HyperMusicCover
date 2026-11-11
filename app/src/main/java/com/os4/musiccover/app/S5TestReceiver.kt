package com.os4.musiccover.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.os4.musiccover.BuildConfig
import java.io.File

/**
 * Stage 5's test hook, and only in a build made with `-PmcS5Test=true`: it does nothing in any
 * other build, which is what lets it sit in the manifest all the time.
 *
 * A test that changes settings has to put the user's own values back. With the app's copy as the
 * source an op no longer writes them down, so the restore goes through the same setters the pages
 * use - [SettingsBackup.importModule] - and then the batch is pushed and the copy dumped:
 *
 * ```
 * adb shell am broadcast -a com.os4.musiccover.S5TEST --es cmd restore
 * adb shell am broadcast -a com.os4.musiccover.S5TEST --es cmd dump
 * ```
 *
 * The file is `s5restore.json` in the app's external files directory, in the same format as an
 * export.
 */
class S5TestReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (!BuildConfig.S5_TEST) return
        val cmd = intent.getStringExtra("cmd") ?: return
        val app = context.applicationContext
        val pending = goAsync()
        Thread({
            try {
                when (cmd) {
                    "dump" -> Log.i(TAG, SettingsStore.dumpLine())
                    "oldmodule" -> {
                        // A module from before stage 5.2: no settings_prefs in its answer, and
                        // the group must not be written. See ModuleBridge.stripsSettings.
                        ModuleBridge.pretendOldModule = intent.getBooleanExtra("on", true)
                        Log.i(TAG, "old module simulation ${ModuleBridge.pretendOldModule}")
                    }
                    "query" -> {
                        // The page's own query, from here: a simulation and a restore both need
                        // the store to see the module's answer without a page being open.
                        val st = kotlinx.coroutines.runBlocking { ModuleBridge.query(app) }
                        Log.i(TAG, "query alive=${st.alive} capable=${st.canReadPrefs} rev=${st.settingsRev}")
                    }
                    "resync" -> {
                        // After s5restore.sh has put the snapshot back into the module's memory:
                        // this makes the app's copy and the group the same before a restart.
                        SettingsStore.resync(app)
                        Thread.sleep(2_500L)
                        Log.i(TAG, "resynced; " + SettingsStore.dumpLine())
                    }
                    "restore" -> {
                        val name = intent.getStringExtra("file") ?: "s5restore.json"
                        val file = File(app.getExternalFilesDir(null), name)
                        SettingsBackup.importModule(app, file.readText())
                        SettingsStore.flushNow(app)
                        // The batch is half a second of quiet plus a report back; the process has
                        // to stay while it goes, or the receiver's end would take it with it.
                        Thread.sleep(2_500L)
                        Log.i(TAG, "restored from ${file.path}; " + SettingsStore.dumpLine())
                    }
                    else -> Log.w(TAG, "unknown cmd $cmd")
                }
            } catch (t: Throwable) {
                Log.w(TAG, "$cmd failed: $t")
            } finally {
                pending.finish()
            }
        }, "s5-test").start()
    }

    private companion object {
        const val TAG = "[MCProbe] s5store: "
    }
}
