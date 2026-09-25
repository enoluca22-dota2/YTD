package com.enoluca.ytd.ui.navigation

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavController
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.enoluca.ytd.data.local.datastore.AppSettings
import com.enoluca.ytd.data.model.AnalysisResult
import com.enoluca.ytd.data.model.DownloadStatus
import com.enoluca.ytd.di.AppContainer
import com.enoluca.ytd.ui.components.MiniDownloadBar
import com.enoluca.ytd.ui.components.MiniDownloadBarHeight
import com.enoluca.ytd.ui.downloads.DownloadsScreen
import com.enoluca.ytd.ui.downloads.DownloadsViewModel
import com.enoluca.ytd.ui.formats.CollectionScreen
import com.enoluca.ytd.ui.formats.MediaDetailScreen
import com.enoluca.ytd.ui.glass.AppBackground
import com.enoluca.ytd.ui.glass.GlassNavItem
import com.enoluca.ytd.ui.glass.GlassNavigationBar
import com.enoluca.ytd.ui.glass.GlassNavigationBarHeight
import com.enoluca.ytd.ui.glass.LocalGlassBackdrop
import com.enoluca.ytd.ui.history.HistoryScreen
import com.enoluca.ytd.ui.history.HistoryViewModel
import com.enoluca.ytd.ui.home.DetectPhase
import com.enoluca.ytd.ui.home.HomeScreen
import com.enoluca.ytd.ui.home.HomeViewModel
import com.enoluca.ytd.ui.settings.SettingsScreen
import com.enoluca.ytd.ui.settings.SettingsViewModel
import com.enoluca.ytd.ui.settings.UpdateDialog
import com.enoluca.ytd.ui.theme.LocalResolvedTheme
import com.enoluca.ytd.ui.theme.VisualStyle
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import kotlinx.coroutines.launch

@Composable
fun YtdNavHost(
    container: AppContainer,
    pendingSharedUrl: String?,
    onSharedUrlConsumed: () -> Unit,
) {
    val navController = rememberNavController()
    val context = LocalContext.current
    val appContext = context.applicationContext

    val homeViewModel: HomeViewModel = viewModel(
        factory = viewModelFactory {
            initializer { HomeViewModel(container.analyzer, container.downloadRepository, container.historyRepository) }
        },
    )
    val downloadsViewModel: DownloadsViewModel = viewModel(
        factory = viewModelFactory { initializer { DownloadsViewModel(container.downloadRepository) } },
    )
    val historyViewModel: HistoryViewModel = viewModel(
        factory = viewModelFactory { initializer { HistoryViewModel(appContext, container.historyRepository) } },
    )
    val settingsViewModel: SettingsViewModel = viewModel(
        factory = viewModelFactory {
            initializer { SettingsViewModel(appContext, container.settingsDataStore, container.historyRepository, container.ytDlpUpdater, container.updateManager) }
        },
    )

    val settings by container.settingsDataStore.settings.collectAsStateWithLifecycle(initialValue = AppSettings())
    val downloads by downloadsViewModel.downloads.collectAsStateWithLifecycle()
    val recent by remember { container.historyRepository.observeRecent(3) }.collectAsStateWithLifecycle(initialValue = emptyList())
    val scope = rememberCoroutineScope()

    // Android 13+: ask for notification permission the first time the user starts a download,
    // when it's clear why we need it — not on first launch.
    var askedForNotifications by rememberSaveable { mutableStateOf(false) }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    fun maybeRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || askedForNotifications || !settings.notificationsEnabled) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        askedForNotifications = true
        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    // Share → app: the shared text (one or many links) goes straight into the analyzer.
    LaunchedEffect(pendingSharedUrl) {
        pendingSharedUrl?.let {
            // A share can arrive while another screen is open; bring Home forward first.
            navController.navigateTopLevel(TopLevelDestination.HOME)
            homeViewModel.consumeSharedText(it)
            onSharedUrlConsumed()
        }
    }

    val homeState by homeViewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(homeState.phase) {
        // Each analysis result opens its own screen. Guarded so recreation (rotation, theme
        // change) never stacks a second copy.
        val result = (homeState.phase as? DetectPhase.Detected)?.result ?: return@LaunchedEffect
        val route = when (result) {
            is AnalysisResult.Single -> Routes.MEDIA_DETAIL
            is AnalysisResult.Collection -> Routes.COLLECTION
        }
        if (navController.currentDestination?.route != route) {
            navController.navigate(route) { launchSingleTop = true }
        }
    }

    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination
    val showBottomBar = TopLevelDestination.entries.any { currentRoute?.hierarchy?.any { d -> d.route == it.route } == true }
    val onDownloadsTab = currentRoute?.hierarchy?.any { it.route == TopLevelDestination.DOWNLOADS.route } == true
    val running = downloads.filter { it.status.isActive }
    val showMiniBar = showBottomBar && !onDownloadsTab && running.isNotEmpty()

    // Leaves a result screen. [submitted] clears the field (the link has been handed to the queue).
    fun leaveResult(submitted: Boolean) {
        homeViewModel.resetDetection(clearUrl = submitted)
        if (submitted) navController.navigateTopLevel(TopLevelDestination.DOWNLOADS) else navController.popBackStack()
    }

    // Result routes compose briefly with an Idle phase during their exit animation, or with no
    // result after process recreation: go back to Home only while the route is really on top.
    @Composable
    fun ReturnHomeIfOrphaned(route: String) {
        LaunchedEffect(Unit) {
            if (navController.currentDestination?.route == route) {
                navController.popBackStack(TopLevelDestination.HOME.route, inclusive = false)
            }
        }
    }

    // Liquid Glass layering (see ui/glass/Glass.kt):
    //  - backgroundBackdrop: just the gradient. In-screen glass cards refract this.
    //  - contentBackdrop: gradient + screens. The floating tab bar refracts this, so list items
    //    visibly bend under it while scrolling.
    val backgroundBackdrop = rememberLayerBackdrop()
    val contentBackdrop = rememberLayerBackdrop()
    val systemBars = WindowInsets.systemBars.asPaddingValues()
    val barSpace = (if (showBottomBar) GlassNavigationBarHeight + 24.dp else 0.dp) +
        (if (showMiniBar) MiniDownloadBarHeight + 8.dp else 0.dp)
    val padding = PaddingValues(
        top = systemBars.calculateTopPadding(),
        bottom = systemBars.calculateBottomPadding() + barSpace,
    )

    // App update dialog (GitHub Releases): shown over any screen when there is something to act on.
    val updateState by container.updateManager.state.collectAsStateWithLifecycle()
    UpdateDialog(updateState, container.updateManager)

    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().layerBackdrop(contentBackdrop)) {
            AppBackground(Modifier.fillMaxSize().layerBackdrop(backgroundBackdrop))
            CompositionLocalProvider(
                LocalGlassBackdrop provides backgroundBackdrop,
                // No opaque Surface sits under the screens anymore, so set the default text color.
                LocalContentColor provides MaterialTheme.colorScheme.onBackground,
            ) {
                NavHost(navController = navController, startDestination = TopLevelDestination.HOME.route) {
                    composable(TopLevelDestination.HOME.route) {
                        HomeScreen(
                            viewModel = homeViewModel,
                            contentPadding = padding,
                            downloads = downloads,
                            recent = recent,
                            clipboardDetection = settings.clipboardDetection,
                            onOpenDownloads = { navController.navigateTopLevel(TopLevelDestination.DOWNLOADS) },
                        )
                    }
                    composable(TopLevelDestination.DOWNLOADS.route) {
                        DownloadsScreen(
                            viewModel = downloadsViewModel,
                            contentPadding = padding,
                            onGoHome = { navController.navigateTopLevel(TopLevelDestination.HOME) },
                        )
                    }
                    composable(TopLevelDestination.HISTORY.route) {
                        HistoryScreen(
                            viewModel = historyViewModel,
                            contentPadding = padding,
                            onRedownload = { url ->
                                navController.navigateTopLevel(TopLevelDestination.HOME)
                                homeViewModel.consumeSharedText(url)
                            },
                        )
                    }
                    composable(TopLevelDestination.SETTINGS.route) {
                        SettingsScreen(viewModel = settingsViewModel, contentPadding = padding)
                    }
                    composable(Routes.MEDIA_DETAIL) {
                        val result = ((homeState.phase as? DetectPhase.Detected)?.result as? AnalysisResult.Single)
                        if (result != null) {
                            MediaDetailScreen(
                                media = result.media,
                                detected = result.detected,
                                askBeforeDownloading = settings.askBeforeDownloading,
                                customFolderSelected = settings.customDownloadTreeUri != null,
                                findExisting = { homeViewModel.findExisting(result.media) },
                                onBack = { leaveResult(submitted = false) },
                                onSubmit = { format, category, fileBaseName, startNow ->
                                    homeViewModel.submitDownload(result.media, format, category, fileBaseName, startNow)
                                    if (startNow) maybeRequestNotificationPermission()
                                },
                                onSubmitted = { leaveResult(submitted = true) },
                            )
                        } else {
                            ReturnHomeIfOrphaned(Routes.MEDIA_DETAIL)
                        }
                    }
                    composable(Routes.COLLECTION) {
                        val result = ((homeState.phase as? DetectPhase.Detected)?.result as? AnalysisResult.Collection)
                        if (result != null) {
                            CollectionScreen(
                                playlist = result.playlist,
                                detected = result.detected,
                                focusedEntry = result.focusedEntry,
                                findDownloaded = { urls -> homeViewModel.existingUrls(urls) },
                                onBack = { leaveResult(submitted = false) },
                                onDownload = { entries, choice ->
                                    val count = homeViewModel.enqueueEntries(result.playlist, entries, choice)
                                    maybeRequestNotificationPermission()
                                    count
                                },
                                onQueued = { leaveResult(submitted = true) },
                            )
                        } else {
                            ReturnHomeIfOrphaned(Routes.COLLECTION)
                        }
                    }
                }
            }
        }

        // Status-bar scrim so scrolled content never runs into the clock: solid for the plain
        // themes (like a normal app bar), a soft fade for the glass themes to stay immersive.
        val theme = LocalResolvedTheme.current
        val scrimColor = MaterialTheme.colorScheme.background
        val plain = theme.style == VisualStyle.PLAIN
        Box(
            Modifier
                .fillMaxWidth()
                .height(systemBars.calculateTopPadding() + if (plain) 0.dp else 12.dp)
                .background(
                    Brush.verticalGradient(
                        listOf(scrimColor.copy(alpha = if (plain) 1f else 0.85f), scrimColor.copy(alpha = if (plain) 1f else 0f)),
                    ),
                ),
        )

        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (showMiniBar) {
                val current = running.first()
                MiniDownloadBar(
                    current = current,
                    othersActive = running.size - 1,
                    onOpen = { navController.navigateTopLevel(TopLevelDestination.DOWNLOADS) },
                    onPause = { scope.launch { container.downloadRepository.pause(current.id) } },
                )
            }
            if (showBottomBar) {
                GlassNavigationBar(
                    items = TopLevelDestination.entries.map { dest ->
                        GlassNavItem(
                            label = dest.label,
                            icon = dest.icon,
                            selected = currentRoute?.hierarchy?.any { it.route == dest.route } == true,
                            onClick = { navController.navigateTopLevel(dest) },
                        )
                    },
                    backdrop = contentBackdrop,
                )
            }
        }
    }
}

private fun NavController.navigateTopLevel(dest: TopLevelDestination) {
    navigate(dest.route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
