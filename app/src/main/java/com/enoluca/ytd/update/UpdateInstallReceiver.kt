package com.enoluca.ytd.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log
import com.enoluca.ytd.YtdApplication

/**
 * Result of the PackageInstaller session started by [UpdateManager]. When Android needs the
 * user's confirmation it hands us its confirmation screen, which we open; the install itself is
 * always confirmed by the user.
 */
class UpdateInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val manager = (context.applicationContext as YtdApplication).container.updateManager
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_INTENT)
                }
                confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)?.let { runCatching { context.startActivity(it) } }
            }
            PackageInstaller.STATUS_SUCCESS -> Unit // the app is replaced and restarted by the system
            PackageInstaller.STATUS_FAILURE_ABORTED -> manager.onInstallFailed("Installation cancelled.")
            else -> {
                Log.w("UpdateInstall", "Install failed: $status ${intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)}")
                manager.onInstallFailed("The update couldn't be installed.")
            }
        }
    }
}
