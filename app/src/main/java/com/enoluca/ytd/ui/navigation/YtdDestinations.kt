package com.enoluca.ytd.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.graphics.vector.ImageVector

enum class TopLevelDestination(val route: String, val label: String, val icon: ImageVector) {
    HOME("home", "Home", Icons.Filled.Home),
    DOWNLOADS("downloads", "Downloads", Icons.Filled.Download),
    LIBRARY("library", "Library", Icons.Filled.LibraryMusic),
    RADIO("radio", "Radio", Icons.Filled.Radio),
    HISTORY("history", "History", Icons.Filled.History),
    SETTINGS("settings", "Settings", Icons.Filled.Settings),
}

object Routes {
    const val MEDIA_DETAIL = "media_detail"
    const val COLLECTION = "collection"
    const val PLAYLIST = "playlist/{id}"
    const val NOW_PLAYING = "now_playing"
    const val VIDEO_PLAYER = "video_player"
    const val RADIO_COUNTRY = "radio/country/{code}"
    const val RADIO_PLAYER = "radio_player"

    fun playlist(id: Long) = "playlist/$id"
    fun radioCountry(code: String) = "radio/country/$code"

    /** The full player for what's loaded: radio, video or music. */
    fun playerFor(isRadio: Boolean, isVideo: Boolean) = when {
        isRadio -> RADIO_PLAYER
        isVideo -> VIDEO_PLAYER
        else -> NOW_PLAYING
    }
}
