package com.enoluca.ytd.ui.library

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.enoluca.ytd.core.FileActions
import com.enoluca.ytd.data.local.db.LibraryMediaEntity
import com.enoluca.ytd.ui.glass.ToastKind
import com.enoluca.ytd.ui.glass.showToast

/** Which sheet/dialog is open for which items. One per screen. */
@Stable
class MediaInteractions {
    /** The item whose actions sheet is open, and the playlist it was opened from (if any). */
    var actionsFor by mutableStateOf<Pair<LibraryMediaEntity, Long?>?>(null)
    var addToPlaylist by mutableStateOf<List<Long>?>(null)
    var infoFor by mutableStateOf<LibraryMediaEntity?>(null)
    var confirmDelete by mutableStateOf<List<Long>?>(null)

    fun showActions(item: LibraryMediaEntity, playlistId: Long? = null) {
        actionsFor = item to playlistId
    }
}

@Composable
fun rememberMediaInteractions() = remember { MediaInteractions() }

/**
 * Renders the sheets/dialogs of [interactions] and the ViewModel's events (toasts, Android's
 * "allow deleting?" dialog). [onPlay] plays one item in the screen's context.
 */
@Composable
fun MediaInteractionsHost(
    interactions: MediaInteractions,
    viewModel: LibraryViewModel,
    snackbar: SnackbarHostState,
    onPlay: ((LibraryMediaEntity) -> Unit)?,
) {
    val context = LocalContext.current
    val playlists by viewModel.playlists.collectAsStateWithLifecycle()
    var pendingConsent by remember { mutableStateOf<List<Long>>(emptyList()) }
    val consentLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        viewModel.onDeleteConsent(pendingConsent, result.resultCode == Activity.RESULT_OK)
        pendingConsent = emptyList()
    }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is LibraryEvent.Message -> snackbar.showToast(event.text, if (event.error) ToastKind.Error else ToastKind.Success)
                is LibraryEvent.ConfirmDelete -> {
                    pendingConsent = event.ids
                    consentLauncher.launch(IntentSenderRequest.Builder(event.intentSender).build())
                }
            }
        }
    }

    interactions.actionsFor?.let { (item, playlistId) ->
        MediaActionsSheet(
            item = item,
            actions = MediaActions(
                onPlay = onPlay?.let { play -> { play(item) } },
                onPlayNext = { viewModel.playNext(listOf(item)) },
                onAddToQueue = { viewModel.addToQueue(listOf(item)) },
                onAddToPlaylist = { interactions.addToPlaylist = listOf(item.id) },
                onToggleFavorite = { viewModel.setFavorite(listOf(item.id), !item.favorite) },
                onRemoveFromPlaylist = playlistId?.let { id -> { viewModel.removeFromPlaylist(id, item.id) } },
                onInfo = { interactions.infoFor = item },
                onShare = { FileActions.share(context, item.uri, item.fileName, item.title) },
                onDelete = { interactions.confirmDelete = listOf(item.id) },
                onForget = { viewModel.forget(listOf(item.id)) },
            ),
            onDismiss = { interactions.actionsFor = null },
        )
    }

    interactions.addToPlaylist?.let { ids ->
        AddToPlaylistSheet(
            playlists = playlists,
            count = ids.size,
            onPick = { viewModel.addToPlaylist(it, ids) },
            onCreate = { name -> viewModel.createPlaylist(name, ids) },
            onDismiss = { interactions.addToPlaylist = null },
        )
    }

    interactions.infoFor?.let { FileInfoDialog(it) { interactions.infoFor = null } }

    interactions.confirmDelete?.let { ids ->
        ConfirmDialog(
            title = if (ids.size == 1) "Delete this file?" else "Delete ${ids.size} files?",
            message = "The media is deleted from your device and removed from the Library and all playlists. This can't be undone.",
            confirmLabel = "Delete",
            onDismiss = { interactions.confirmDelete = null },
            onConfirm = { viewModel.delete(ids) },
        )
    }
}
