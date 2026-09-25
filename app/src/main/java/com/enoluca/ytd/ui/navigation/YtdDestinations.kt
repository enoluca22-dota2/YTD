package com.enoluca.ytd.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.graphics.vector.ImageVector

enum class TopLevelDestination(val route: String, val label: String, val icon: ImageVector) {
    HOME("home", "Home", Icons.Filled.Home),
    DOWNLOADS("downloads", "Downloads", Icons.Filled.Download),
    HISTORY("history", "History", Icons.Filled.History),
    SETTINGS("settings", "Settings", Icons.Filled.Settings),
}

object Routes {
    const val MEDIA_DETAIL = "media_detail"
    const val COLLECTION = "collection"
}
