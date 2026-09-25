package com.enoluca.ytd.ui.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import com.enoluca.ytd.update.UpdateManager
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.enoluca.ytd.BuildConfig
import com.enoluca.ytd.data.local.datastore.AppSettings
import com.enoluca.ytd.data.local.datastore.NetworkPolicy
import com.enoluca.ytd.data.local.datastore.SpeedLimit
import com.enoluca.ytd.ui.components.ScreenHeader
import com.enoluca.ytd.ui.glass.GlassSurface

@Composable
fun SettingsScreen(viewModel: SettingsViewModel, contentPadding: PaddingValues) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri != null) {
            // Some folder providers don't offer persistable grants; then keep the default location.
            val persisted = runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }.isSuccess
            if (persisted) viewModel.setCustomDownloadTreeUri(uri.toString())
        }
    }

    LazyColumn(
        contentPadding = PaddingValues(
            start = 16.dp, end = 16.dp, top = contentPadding.calculateTopPadding(),
            bottom = contentPadding.calculateBottomPadding() + 8.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item { ScreenHeader("Settings", startPadding = 4.dp) }

        item {
            SettingsSection("Downloads") {
                StepperRow(
                    label = "Parallel downloads",
                    value = settings.concurrentDownloads,
                    onDecrease = { viewModel.setConcurrentDownloads(settings.concurrentDownloads - 1) },
                    onIncrease = { viewModel.setConcurrentDownloads(settings.concurrentDownloads + 1) },
                )
                SwitchRow(
                    label = "Show download options before starting",
                    checked = settings.askBeforeDownloading,
                    onCheckedChange = viewModel::setAskBeforeDownloading,
                )
                SwitchRow(
                    label = "Detect media links on the clipboard",
                    checked = settings.clipboardDetection,
                    onCheckedChange = viewModel::setClipboardDetection,
                )
                Text("Download speed", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 8.dp))
                RadioGroupRow(
                    options = SpeedLimit.entries.map { it to it.label },
                    selected = settings.speedLimit,
                    onSelect = viewModel::setSpeedLimit,
                )
                Column(Modifier.padding(vertical = 10.dp)) {
                    Text("Download folder", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        settings.customDownloadTreeUri?.let { "Custom folder selected" } ?: "Default (Movies / Music / Download / ENAGELYUCA)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { folderPicker.launch(null) }) { Text("Choose folder") }
                        if (settings.customDownloadTreeUri != null) {
                            OutlinedButton(onClick = { viewModel.setCustomDownloadTreeUri(null) }) { Text("Use default") }
                        }
                    }
                }
            }
        }

        item {
            SettingsSection("Appearance") {
                ThemePicker(
                    selected = settings.themeMode,
                    onSelect = viewModel::setThemeMode,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }
        }

        item {
            SettingsSection("Notifications") {
                SwitchRow("Enable notifications", settings.notificationsEnabled, viewModel::setNotificationsEnabled)
                SwitchRow("Completion notifications", settings.notifyOnCompletion, viewModel::setNotifyOnCompletion)
                SwitchRow("Error notifications", settings.notifyOnError, viewModel::setNotifyOnError)
            }
        }

        item {
            SettingsSection("Network") {
                RadioGroupRow(
                    options = listOf(
                        NetworkPolicy.ANY_NETWORK to "Download on any network",
                        NetworkPolicy.WIFI_ONLY to "Wi-Fi only (downloads wait on mobile data)",
                    ),
                    selected = settings.networkPolicy,
                    onSelect = viewModel::setNetworkPolicy,
                )
            }
        }

        item {
            SettingsSection("Advanced") {
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { viewModel.clearHistory() }) { Text("Clear history") }
                    OutlinedButton(onClick = { viewModel.clearTempFiles() }) { Text("Clear temp files") }
                }
                SwitchRow("Debug logging (yt-dlp output to Logcat)", settings.debugLoggingEnabled, viewModel::setDebugLoggingEnabled)
                val updateState by viewModel.providerUpdateState.collectAsStateWithLifecycle()
                Column(Modifier.padding(vertical = 10.dp)) {
                    Text("Provider: yt-dlp (${viewModel.providerVersion()})", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "Sites like YouTube change often; if downloads start failing with 403/format errors, update the extractor.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(onClick = { viewModel.updateProvider() }, enabled = updateState != ProviderUpdateState.CHECKING) {
                            Text(if (updateState == ProviderUpdateState.CHECKING) "Checking…" else "Update yt-dlp")
                        }
                        val statusText = when (updateState) {
                            ProviderUpdateState.UPDATED -> "Updated to the latest version"
                            ProviderUpdateState.ALREADY_CURRENT -> "Already up to date"
                            ProviderUpdateState.BUSY -> "Wait until downloads finish, then try again"
                            ProviderUpdateState.OFFLINE -> "You're offline"
                            ProviderUpdateState.FAILED -> "Update failed — the current version is still used"
                            else -> null
                        }
                        statusText?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    }
                    val ffmpegVersion by produceState("…") { value = viewModel.ffmpegVersion() }
                    Text("FFmpeg: $ffmpegVersion", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp))
                    Text("About ENAGELYUCA — v${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodyMedium)
                    AppUpdateRow(viewModel.updateManager)
                }
            }
        }
    }
}

/** One glass card per settings group. */
@Composable
private fun SettingsSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    GlassSurface(Modifier.fillMaxWidth(), cornerRadius = 24.dp) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(bottom = 4.dp).semantics { heading() },
            )
            content()
        }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange)
            .padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
private fun StepperRow(label: String, value: Int, onDecrease: () -> Unit, onIncrease: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(
                onClick = onDecrease,
                enabled = value > AppSettings.MIN_CONCURRENT_DOWNLOADS,
                modifier = Modifier.semantics { contentDescription = "Fewer parallel downloads" },
            ) { Text("-") }
            Text(value.toString(), style = MaterialTheme.typography.titleMedium)
            OutlinedButton(
                onClick = onIncrease,
                enabled = value < AppSettings.MAX_CONCURRENT_DOWNLOADS,
                modifier = Modifier.semantics { contentDescription = "More parallel downloads" },
            ) { Text("+") }
        }
    }
}

@Composable
private fun <T> RadioGroupRow(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    Column {
        options.forEach { (value, label) ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .selectable(selected = value == selected, role = Role.RadioButton, onClick = { onSelect(value) })
                    .padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = value == selected, onClick = null)
                Text(label, style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

/** "Check for Updates" (GitHub Releases) + the optional daily check. The dialog itself is app-wide. */
@Composable
private fun AppUpdateRow(manager: UpdateManager) {
    val state by manager.state.collectAsStateWithLifecycle()
    var autoCheck by remember { mutableStateOf(manager.autoCheckEnabled) }
    Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedButton(onClick = manager::checkNow, enabled = state !is UpdateManager.State.Checking && state !is UpdateManager.State.Downloading) {
            Text(if (state is UpdateManager.State.Checking) "Checking…" else "Check for Updates")
        }
        val status = when (val s = state) {
            is UpdateManager.State.UpToDate -> "You're using the latest version."
            UpdateManager.State.NotConfigured -> "Updates aren't set up in this build."
            is UpdateManager.State.Failed -> if (s.release == null) s.message else null
            is UpdateManager.State.Downloading -> "Downloading update…"
            else -> null
        }
        status?.let { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f)) }
    }
    SwitchRow("Check for updates automatically (once a day)", autoCheck) {
        autoCheck = it
        manager.autoCheckEnabled = it
    }
}
