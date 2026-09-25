package com.enoluca.ytd.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.enoluca.ytd.core.Formatting
import com.enoluca.ytd.update.ReleaseInfo
import com.enoluca.ytd.update.UpdateManager
import com.enoluca.ytd.update.UpdateManager.State
import com.enoluca.ytd.ui.glass.GlassProgressBar
import com.enoluca.ytd.ui.glass.sheetContainerColor

/**
 * The update dialog, shown app-wide (from the root of the navigation) whenever the updater has
 * something to say: a newer release, its download progress, verification or the install step.
 * Plain states (checking / up to date / not configured) are shown inline in Settings instead.
 */
@Composable
fun UpdateDialog(state: State, manager: UpdateManager) {
    val release: ReleaseInfo = when (state) {
        is State.Available -> state.release
        is State.Downloading -> state.release
        is State.Verifying -> state.release
        is State.ReadyToInstall -> state.release
        is State.Failed -> state.release ?: return
        else -> return
    }
    val busy = state is State.Downloading || state is State.Verifying
    // Back from "Install unknown apps": pick up the new permission.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { manager.refreshInstallPermission() }
    AlertDialog(
        onDismissRequest = { if (!busy) manager.dismiss() },
        properties = DialogProperties(dismissOnClickOutside = !busy),
        containerColor = sheetContainerColor(),
        title = { Text(if (state is State.Downloading || state is State.Verifying) "Downloading update…" else "Update available") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("New version: ${release.version}", style = MaterialTheme.typography.bodyMedium)
                Text("Current version: ${manager.currentVersion}", style = MaterialTheme.typography.bodyMedium)
                release.apk.sizeBytes?.let {
                    Text("Size: ${Formatting.bytes(it)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                when (state) {
                    is State.Downloading -> {
                        // Real bytes from the HTTP transfer; indeterminate until the size is known.
                        GlassProgressBar(progress = state.fraction)
                        Text(
                            state.fraction?.let {
                                "${Formatting.percent(it * 100f)} — ${Formatting.bytes(state.downloadedBytes.coerceAtLeast(1))} / ${Formatting.bytes(state.totalBytes)}"
                            } ?: "Downloading… ${Formatting.bytes(state.downloadedBytes.coerceAtLeast(1))}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    is State.Verifying -> {
                        GlassProgressBar(progress = null)
                        Text("Verifying…", style = MaterialTheme.typography.bodySmall)
                    }
                    is State.ReadyToInstall -> Text(
                        if (state.needsPermission) {
                            "To install updates, allow ENAGELYUCA to install apps (Android asks once), then tap Install."
                        } else {
                            "Downloaded and verified. Android will ask you to confirm the installation."
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                    is State.Failed -> Text(state.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    else -> Unit
                }
                if (release.notes.isNotBlank() && state !is State.Downloading) {
                    Text("What's new", style = MaterialTheme.typography.labelLarge)
                    Column(Modifier.heightIn(max = 240.dp).verticalScroll(rememberScrollState())) {
                        // Published release notes, verbatim.
                        Text(release.notes, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        },
        confirmButton = {
            when (state) {
                is State.Available -> TextButton(onClick = manager::download) { Text("Update Now") }
                is State.Failed -> TextButton(onClick = manager::download) { Text("Try again") }
                is State.ReadyToInstall -> if (state.needsPermission) {
                    TextButton(onClick = manager::openInstallPermissionSettings) { Text("Allow") }
                } else {
                    TextButton(onClick = manager::installReady) { Text("Install") }
                }
                else -> Unit
            }
        },
        dismissButton = {
            when (state) {
                is State.Available -> TextButton(onClick = manager::later) { Text("Later") }
                is State.Downloading -> TextButton(onClick = manager::cancelDownload) { Text("Cancel") }
                is State.Verifying -> Unit
                else -> TextButton(onClick = manager::dismiss) { Text("Close") }
            }
        },
    )
}
