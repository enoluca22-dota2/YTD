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
 * always confirmed by the user. Every outcome other than success reaches the update dialog.
 */
class UpdateInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val manager = (context.applicationContext as YtdApplication).container.updateManager
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val detail = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_INTENT)
                }
                val opened = confirm != null && runCatching {
                    context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }.onFailure { Log.w(TAG, "Couldn't open the installer confirmation", it) }.isSuccess
                // Typically blocked when the app went to the background during the download.
                if (!opened) manager.onInstallFailed("Android's installer could not be opened. Keep ENAGELYUCA open and tap Try again.")
            }
            PackageInstaller.STATUS_SUCCESS -> Unit // the app is replaced and restarted by the system
            else -> {
                Log.w(TAG, "Install failed: $status $detail")
                manager.onInstallFailed(messageFor(status, detail))
            }
        }
    }

    private fun messageFor(status: Int, detail: String?): String = when (status) {
        PackageInstaller.STATUS_FAILURE_ABORTED -> "Installation cancelled."
        PackageInstaller.STATUS_FAILURE_BLOCKED -> "Android blocked the installation (device policy or Play Protect)."
        PackageInstaller.STATUS_FAILURE_CONFLICT ->
            "Android refused the update: it conflicts with the installed app (usually a different signing key). " +
                "Uninstall this copy and install the APK from GitHub once."
        PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> "This update isn't compatible with this device."
        PackageInstaller.STATUS_FAILURE_INVALID -> "Android reported the downloaded APK as invalid. Try again."
        PackageInstaller.STATUS_FAILURE_STORAGE -> "Not enough storage to install the update. Free some space and try again."
        else -> "The update couldn't be installed" + (detail?.takeIf { it.isNotBlank() }?.let { ": $it" } ?: ".")
    }

    private companion object {
        const val TAG = "UpdateInstall"
    }
}
