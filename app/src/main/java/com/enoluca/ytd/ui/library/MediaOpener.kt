package com.enoluca.ytd.ui.library

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * "Open" for a downloaded file anywhere in the app (Home, Downloads, History): plays it in
 * ENAGELYUCA's own player when it's in the Library, otherwise hands it to another app.
 * [onFailed] runs when nothing could open it.
 */
fun interface MediaOpener {
    fun open(uri: String, fileName: String?, onFailed: () -> Unit)
}

val LocalMediaOpener = staticCompositionLocalOf<MediaOpener> {
    MediaOpener { _, _, onFailed -> onFailed() }
}
