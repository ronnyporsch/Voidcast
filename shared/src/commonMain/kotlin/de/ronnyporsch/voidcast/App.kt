package de.ronnyporsch.voidcast

import androidx.compose.foundation.focusable
import de.ronnyporsch.voidcast.domain.model.PlaybackStatus
import de.ronnyporsch.voidcast.platform.rememberSystemFullscreen
import de.ronnyporsch.voidcast.ui.screen.player.VideoPlayerView
import de.ronnyporsch.voidcast.ui.screen.player.FullscreenVideoControls
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.foundation.layout.*
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.ui.input.key.*
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import coil3.ImageLoader
import coil3.compose.setSingletonImageLoaderFactory
import coil3.network.ktor3.KtorNetworkFetcherFactory
import de.ronnyporsch.voidcast.data.youtube.YouTubeService
import de.ronnyporsch.voidcast.domain.model.youTubeArtistId
import de.ronnyporsch.voidcast.domain.model.Album
import de.ronnyporsch.voidcast.domain.model.Song
import de.ronnyporsch.voidcast.domain.model.isYouTubeId
import de.ronnyporsch.voidcast.platform.GlobalShortcuts
import de.ronnyporsch.voidcast.platform.createHttpClient
import de.ronnyporsch.voidcast.platform.installGlobalShortcutListener
import de.ronnyporsch.voidcast.ui.CoverArtUrlProvider
import de.ronnyporsch.voidcast.ui.LocalCoverArtUrlProvider
import de.ronnyporsch.voidcast.ui.LocalMusicRepository
import de.ronnyporsch.voidcast.ui.navigation.Navigator
import de.ronnyporsch.voidcast.ui.navigation.QueueTab
import de.ronnyporsch.voidcast.ui.navigation.DownloadsTab
import de.ronnyporsch.voidcast.ui.navigation.Screen
import de.ronnyporsch.voidcast.ui.screen.albums.*
import de.ronnyporsch.voidcast.ui.screen.library.*
import de.ronnyporsch.voidcast.ui.screen.login.*
import de.ronnyporsch.voidcast.ui.screen.player.*
import de.ronnyporsch.voidcast.ui.screen.playlist.*
import de.ronnyporsch.voidcast.ui.screen.search.*
import de.ronnyporsch.voidcast.ui.screen.settings.*
import de.ronnyporsch.voidcast.ui.theme.VoidcastTheme
import de.ronnyporsch.voidcast.ui.screen.favorites.FavoriteSongsScreen
import de.ronnyporsch.voidcast.ui.screen.downloads.DownloadsButton
import de.ronnyporsch.voidcast.ui.screen.downloads.DownloadsScreen
import de.ronnyporsch.voidcast.ui.components.AppSnackbarHost
import de.ronnyporsch.voidcast.ui.components.ServerUnreachableButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import de.ronnyporsch.voidcast.ui.components.LocalFavorites
import de.ronnyporsch.voidcast.ui.components.LocalPlaylistEditor
import de.ronnyporsch.voidcast.ui.components.PendingYouTubeAdditionDialog
import de.ronnyporsch.voidcast.ui.components.TooltipIconButton
import de.ronnyporsch.voidcast.ui.components.LocalAddToQueue
import de.ronnyporsch.voidcast.ui.components.LocalAlbumSongs
import de.ronnyporsch.voidcast.ui.components.LocalStreamFormatLookup
import de.ronnyporsch.voidcast.ui.components.description
import de.ronnyporsch.voidcast.ui.components.LocalSongDragAndDrop
import de.ronnyporsch.voidcast.ui.components.LocalScrollPositions
import de.ronnyporsch.voidcast.ui.components.LocalDownloadedSongIds
import de.ronnyporsch.voidcast.ui.components.LocalSongDownloads
import de.ronnyporsch.voidcast.ui.components.LocalPendingSongDownloads
import de.ronnyporsch.voidcast.ui.components.PendingSongDownloads
import de.ronnyporsch.voidcast.ui.components.SongDragAndDrop
import de.ronnyporsch.voidcast.ui.components.SongDragOverlay
import de.ronnyporsch.voidcast.ui.components.PlaylistEditor
import de.ronnyporsch.voidcast.ui.components.onMouseNavigation
import de.ronnyporsch.voidcast.ui.components.LocalSelectionBarHost
import de.ronnyporsch.voidcast.ui.components.SelectionActionBar
import de.ronnyporsch.voidcast.ui.components.SelectionBarHost
import de.ronnyporsch.voidcast.ui.theme.AccentColor
import de.ronnyporsch.voidcast.ui.theme.SearchPlaceholderGray
import de.ronnyporsch.voidcast.ui.theme.SpotifyLightGray
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.CancellationException

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun App() {
    // Cover art is loaded from the music server, whose certificate may only be accepted by our HTTP client
    setSingletonImageLoaderFactory { context ->
        ImageLoader.Builder(context)
            .components { add(KtorNetworkFetcherFactory(httpClient = { createHttpClient() })) }
            .build()
    }
    // The connection and the player outlive the UI, e.g. an Android activity that gets closed while playing
    val session = remember { AppSession.get() }
    val settingsHolder = session.settingsHolder
    val connection = session.connection
    val repository = connection.repository
    val youTube = remember { YouTubeService(connection) }
    val youTubeCapabilities by youTube.capabilities.collectAsState(youTube.currentCapabilities)
    // With saved login info, start directly on the library while auto-login is still connecting in the background
    val loggedIn = remember { session.autoLoginStatus.value.let { it != AutoLoginStatus.None && it !is AutoLoginStatus.Rejected } }
    val navigator = remember { Navigator(if (loggedIn) Screen.Library() else Screen.Login) }
    val playerViewModel = session.playerViewModel
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val playlistEditor = remember {
        PlaylistEditor(
            repository,
            scope,
            youTube,
            onPlaylistChanged = repository::changed,
            onOpenPlaylist = { navigator.push(Screen.PlaylistSongs(it)) },
        ) { message ->
            snackbarHostState.showSnackbar(message, duration = SnackbarDuration.Short)
        }
    }
    val addToQueue: (List<Song>) -> Unit = remember {
        { songs ->
            playerViewModel.onEvent(PlayerEvent.AddToQueue(songs))
            val what = songs.singleOrNull()?.let { "\"${it.title}\"" } ?: "${songs.size} songs"
            scope.launch { snackbarHostState.showSnackbar("Added $what to the queue", duration = SnackbarDuration.Short) }
        }
    }
    val lookUpStreamFormat: (List<Song>) -> Unit = remember {
        { songs ->
            scope.launch {
                // Each lookup runs yt-dlp on the server; the rows show the formats as they arrive
                val formats = songs.map { song ->
                    async { runCatching { youTube.lookUpStreamFormat(song) }.onFailure { if (it is CancellationException) throw it } }
                }.awaitAll()
                val single = songs.singleOrNull()
                val message = when {
                    single != null -> formats.single().fold(
                        onSuccess = { format -> format?.description()?.let { "\"${single.title}\": $it" } ?: "No audio format known for \"${single.title}\"" },
                        onFailure = { "Couldn't look up the audio format: ${it.message}" },
                    )
                    formats.any { it.isFailure } -> "Couldn't look up the audio format of ${formats.count { it.isFailure }} of ${songs.size} songs"
                    else -> "Looked up the audio format of ${songs.size} songs"
                }
                snackbarHostState.showSnackbar(message, duration = SnackbarDuration.Short)
            }
        }
    }
    val loadAlbumSongs: suspend (List<Album>) -> List<Song> = remember {
        { albums ->
            val loaded = coroutineScope {
                albums.map { album ->
                    async {
                        when {
                            album.songs.isNotEmpty() -> Result.success(album.songs)
                            album.id.isYouTubeId -> runCatching { youTube.getAlbum(album.id).songs }
                                .onFailure { if (it is CancellationException) throw it }
                            else -> repository.getAlbum(album.id).map { it.songs }
                        }
                    }
                }.awaitAll()
            }
            val failed = loaded.count { it.isFailure }
            if (failed > 0) {
                val what = albums.singleOrNull()?.let { "\"${it.name}\"" } ?: "$failed of ${albums.size} albums"
                scope.launch { snackbarHostState.showSnackbar("Couldn't load the songs of $what", duration = SnackbarDuration.Short) }
            }
            loaded.flatMap { it.getOrDefault(emptyList()) }
        }
    }
    val libraryViewModel = remember {
        LibraryViewModel(
            repository,
            awaitingConnection = loggedIn,
            playlistChanges = repository.playlistChanges,
            downloads = connection.downloads,
            favorites = connection.favorites,
            initialView = settingsHolder.settings.value.libraryView,
            onViewChanged = { view -> settingsHolder.update { copy(libraryView = view) } },
        )
    }
    val searchViewModel = remember { SearchViewModel(repository, youTube) }
    val coverArtUrlProvider = remember { CoverArtUrlProvider(repository) }
    // Touch drags scroll, so songs can only be dragged with a mouse
    val songDragAndDrop = remember { if (getPlatform().isMobile) null else SongDragAndDrop() }

    // (Re)load the library whenever a server got connected, it may be a different one
    LaunchedEffect(connection) {
        connection.connections.collect { count ->
            if (count > 0) libraryViewModel.onEvent(LibraryEvent.Refresh)
        }
    }

    LaunchedEffect(session) {
        session.autoLoginStatus.collect { status ->
            when (status) {
                is AutoLoginStatus.Rejected -> if (navigator.currentScreen.value !is Screen.Login) {
                    navigator.replaceAll(Screen.Login)
                    snackbarHostState.showSnackbar("Login failed: ${status.message}", withDismissAction = true)
                }
                // Meanwhile show the playlists stored on the device; the top bar tells why
                is AutoLoginStatus.Retrying -> libraryViewModel.onEvent(LibraryEvent.ConnectionFailed)
                AutoLoginStatus.None, AutoLoginStatus.Connecting, AutoLoginStatus.Connected -> {}
            }
        }
    }

    LaunchedEffect(libraryViewModel) {
        libraryViewModel.effects.collectLatest { effect ->
            when (effect) {
                is LibraryEffect.NavigateToArtist ->
                    navigator.push(Screen.ArtistAlbums(effect.artistId, effect.artistName))
                is LibraryEffect.NavigateToAlbum ->
                    navigator.push(Screen.AlbumSongs(effect.albumId))
                is LibraryEffect.NavigateToPlaylist ->
                    navigator.push(Screen.PlaylistSongs(effect.playlistId))
                LibraryEffect.NavigateToFavoriteSongs ->
                    navigator.push(Screen.FavoriteSongs)
                is LibraryEffect.NavigateToGenre ->
                    navigator.push(Screen.GenreSongs(effect.genre))
                is LibraryEffect.PlaySongs ->
                    playSongs(playerViewModel, effect.songs, effect.startIndex, effect.shuffle)
            }
        }
    }

    LaunchedEffect(connection) {
        connection.favorites.errors.collect { message ->
            snackbarHostState.showSnackbar(message, duration = SnackbarDuration.Short)
        }
    }

    LaunchedEffect(playerViewModel) {
        playerViewModel.effects.collect { effect ->
            when (effect) {
                is PlayerEffect.PlaybackError ->
                    snackbarHostState.showSnackbar("Playback failed: ${effect.message}", withDismissAction = true)
                is PlayerEffect.Message ->
                    snackbarHostState.showSnackbar(effect.message, duration = SnackbarDuration.Short)
            }
        }
    }

    LaunchedEffect(playerViewModel) {
        installGlobalShortcutListener()
        GlobalShortcuts.skip.collect { next -> playerViewModel.onEvent(if (next) PlayerEvent.Next else PlayerEvent.Previous) }
    }
    LaunchedEffect(playerViewModel) {
        GlobalShortcuts.playPause.collect { playerViewModel.onEvent(PlayerEvent.PlayPause) }
    }

    LaunchedEffect(searchViewModel) {
        searchViewModel.effects.collectLatest { effect ->
            when (effect) {
                is SearchEffect.NavigateToArtist ->
                    navigator.push(Screen.ArtistAlbums(effect.artistId, effect.artistName))
                is SearchEffect.NavigateToAlbum ->
                    navigator.push(Screen.AlbumSongs(effect.albumId))
                is SearchEffect.NavigateToPlaylist ->
                    navigator.push(Screen.PlaylistSongs(effect.playlistId))
                is SearchEffect.PlaySongs ->
                    playSongs(playerViewModel, effect.songs, effect.startIndex, null)
            }
        }
    }

    val downloadedSongIds by connection.downloads.downloadedSongIds.collectAsState()
    val queuedSongIds by connection.downloads.queuedSongIds.collectAsState()
    val downloadingSongIds by connection.downloads.downloadingSongIds.collectAsState()
    val pendingSongDownloads = remember(queuedSongIds, downloadingSongIds) {
        PendingSongDownloads(queuedSongIds, downloadingSongIds)
    }

    val selectionBarHost = remember { SelectionBarHost() }
    val autoLoginStatus by session.autoLoginStatus.collectAsState()

    // Pulling down a screen (mobile) or the refresh button (desktop) tries to reach the server again, or once
    // connected reloads what the screen shows
    var screenGeneration by remember { mutableIntStateOf(0) }
    val refresh: suspend () -> Unit = {
        val status = session.autoLoginStatus.value
        val screen = navigator.currentScreen.value
        // Desktop: the sidebar next to the screen shows the library as well
        if (screen !is Screen.Library && !getPlatform().isMobile && status is AutoLoginStatus.Connected) {
            libraryViewModel.onEvent(LibraryEvent.Refresh)
        }
        when {
            status is AutoLoginStatus.Retrying -> {
                session.retryConnection()
                session.autoLoginStatus.first { it != status }
            }
            status == AutoLoginStatus.Connecting -> session.autoLoginStatus.first { it != status }
            else -> when (screen) {
                is Screen.Library -> coroutineScope {
                    launch { connection.favorites.reload() }
                    libraryViewModel.onEvent(LibraryEvent.Refresh)
                    libraryViewModel.state.first { !it.isLoading }
                }
                Screen.FavoriteSongs -> connection.favorites.reload()
                Screen.Search -> searchViewModel.onEvent(SearchEvent.Search)
                // Their view models load everything again when created
                else -> screenGeneration++
            }
        }
    }

    var refreshing by remember { mutableStateOf(false) }
    val startRefresh = {
        if (!refreshing) {
            refreshing = true
            scope.launch {
                try {
                    refresh()
                } finally {
                    refreshing = false
                }
            }
        }
    }
    LaunchedEffect(Unit) {
        GlobalShortcuts.refresh.collect {
            val screen = navigator.currentScreen.value
            if (screen !is Screen.Login && screen !is Screen.Player) startRefresh()
        }
    }

    VoidcastTheme {
        CompositionLocalProvider(
            LocalSelectionBarHost provides selectionBarHost,
            LocalCoverArtUrlProvider provides coverArtUrlProvider,
            LocalMusicRepository provides repository,
            LocalPlaylistEditor provides playlistEditor,
            LocalAddToQueue provides addToQueue,
            LocalAlbumSongs provides loadAlbumSongs,
            LocalStreamFormatLookup provides lookUpStreamFormat.takeIf { youTubeCapabilities.available },
            LocalFavorites provides connection.favorites,
            LocalSongDragAndDrop provides songDragAndDrop,
            LocalDownloadedSongIds provides downloadedSongIds,
            LocalPendingSongDownloads provides pendingSongDownloads,
            LocalSongDownloads provides connection.downloads.takeIf { it.isSupported },
        ) {
        Box(Modifier.fillMaxSize()) {
        PendingYouTubeAdditionDialog(playlistEditor)
        val currentEntry by navigator.currentEntry.collectAsState()
        val currentScreen = currentEntry.screen
        val playerState by playerViewModel.state.collectAsState()
        val hasSong = playerState.playback.currentSong != null
        val isMobile = remember { getPlatform().isMobile }

        // System back (Android back button/gesture) navigates within the app while there is a previous screen.
        // Keyed on currentScreen so this scope recomposes on navigation (the back stack itself isn't observable)
        val canGoBack = remember(currentScreen) { navigator.canGoBack() }
        NavigationBackHandler(
            state = rememberNavigationEventState(NavigationEventInfo.None),
            isBackEnabled = canGoBack,
            onBackCompleted = { navigator.pop() },
        )

        // The video of the current song, over the whole app (and screen)
        var videoFullscreen by remember { mutableStateOf(false) }
        // Entered by turning the phone sideways; turning it back leaves it again
        var fullscreenByRotation by remember { mutableStateOf(false) }
        val systemFullscreen = rememberSystemFullscreen(onExited = { videoFullscreen = false })
        val setVideoFullscreen: (Boolean) -> Unit = { enabled ->
            videoFullscreen = enabled
            fullscreenByRotation = false
            // Right away: browsers only allow fullscreen while handling the click
            systemFullscreen.set(enabled, landscape = (playerState.videoAspectRatio ?: (16f / 9f)) >= 1f)
        }
        val showVideoFullscreen = videoFullscreen && playerState.isVideo && hasSong
        LaunchedEffect(showVideoFullscreen) {
            if (!showVideoFullscreen && videoFullscreen) setVideoFullscreen(false)
        }
        // Phones: a video on the player screen goes fullscreen in landscape orientation, like in YouTube's app
        val windowSize = LocalWindowInfo.current.containerSize
        val isLandscape = windowSize.width > windowSize.height
        val videoOnPlayerScreen = currentScreen is Screen.Player && playerState.isVideo
        LaunchedEffect(isLandscape, videoOnPlayerScreen) {
            if (!isMobile) return@LaunchedEffect
            if (isLandscape && videoOnPlayerScreen && !videoFullscreen) {
                videoFullscreen = true
                fullscreenByRotation = true
                // Without locking the orientation, so turning the phone back works
                systemFullscreen.set(true, landscape = false)
            } else if (!isLandscape && fullscreenByRotation) {
                setVideoFullscreen(false)
            }
        }
        // Back leaves fullscreen first
        NavigationBackHandler(
            state = rememberNavigationEventState(NavigationEventInfo.None),
            isBackEnabled = showVideoFullscreen,
            onBackCompleted = { setVideoFullscreen(false) },
        )
        // Back while the keyboard is open (e.g. its hide button) only closes it. The keyboard should get it before
        // the app, but a handler that got enabled after the keyboard opened (typing opens the search screen) wins
        val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
        val focusManager = LocalFocusManager.current
        val keyboard = LocalSoftwareKeyboardController.current
        NavigationBackHandler(
            state = rememberNavigationEventState(NavigationEventInfo.None),
            isBackEnabled = imeVisible,
            onBackCompleted = {
                keyboard?.hide()
                focusManager.clearFocus()
            },
        )

        // Back ends a selection (mobile) first
        val selectionBar = selectionBarHost.current
        NavigationBackHandler(
            state = rememberNavigationEventState(NavigationEventInfo.None),
            isBackEnabled = selectionBar != null,
            onBackCompleted = { selectionBarHost.current?.onClear() },
        )

        Scaffold(
            // Mouse back/forward buttons work anywhere in the window, including the top/bottom bars
            modifier = Modifier.onMouseNavigation(
                onBack = { if (navigator.canGoBack()) navigator.pop() },
                onForward = { if (navigator.canGoForward()) navigator.forward() }
            ),
            containerColor = MaterialTheme.colorScheme.background,
            snackbarHost = { AppSnackbarHost(snackbarHostState) },
            topBar = {
                if (currentScreen !is Screen.Login && currentScreen !is Screen.Player) {
                    val searchState by searchViewModel.state.collectAsState()
                    val searchFocusRequester = remember { FocusRequester() }
                    LaunchedEffect(Unit) {
                        installGlobalShortcutListener()
                        GlobalShortcuts.focusSearch.collect { searchFocusRequester.requestFocus() }
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .windowInsetsPadding(WindowInsets.systemBars.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // These screens draw their own back button in their header
                        val hasOwnBackButton = currentScreen is Screen.ArtistAlbums || currentScreen is Screen.AlbumSongs ||
                            currentScreen is Screen.PlaylistSongs || currentScreen is Screen.Settings ||
                            currentScreen is Screen.FavoriteSongs || currentScreen is Screen.GenreSongs
                        if (navigator.canGoBack() && !hasOwnBackButton) {
                            IconButton(onClick = { navigator.pop() }) {
                                Icon(
                                    imageVector = Icons.Default.ArrowBack,
                                    contentDescription = "Back",
                                    tint = MaterialTheme.colorScheme.onBackground
                                )
                            }
                        }

                        OutlinedTextField(
                            value = searchState.query,
                            onValueChange = {
                                searchViewModel.onEvent(SearchEvent.QueryChanged(it))
                                // Results only show while there is a query; clearing it goes back
                                if (it.isEmpty()) {
                                    if (currentScreen is Screen.Search) navigator.pop()
                                } else if (currentScreen !is Screen.Search) {
                                    navigator.push(Screen.Search)
                                }
                            },
                            // Explicit color: the theme's text styles hardcode white, which overrides the placeholder color
                            placeholder = { Text("Search artists, albums, songs...", color = SearchPlaceholderGray) },
                            modifier = Modifier.weight(1f).focusRequester(searchFocusRequester),
                            singleLine = true,
                            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = AccentColor,
                                cursorColor = AccentColor,
                            ),
                            shape = RoundedCornerShape(8.dp),
                        )
                        // Touch screens pull the screen down instead
                        if (!isMobile) {
                            TooltipIconButton(
                                onClick = startRefresh,
                                icon = Icons.Default.Refresh,
                                contentDescription = "Refresh (F5)",
                                tint = SpotifyLightGray,
                                enabled = !refreshing,
                            )
                        }
                        val deviceDownloads = connection.downloads.takeIf { it.isSupported }
                        DownloadsButton(youTube, deviceDownloads, isOpen = currentScreen is Screen.Downloads) {
                            if (currentScreen is Screen.Downloads) {
                                navigator.pop()
                            } else {
                                val youTubeDownloads = youTube.requestedSongs.value.isNotEmpty() || deviceDownloads == null
                                navigator.push(Screen.Downloads(if (youTubeDownloads) DownloadsTab.YouTube else DownloadsTab.Device))
                            }
                        }
                        // While something plays, the mini player has it
                        if (!hasSong) DevicesButton(playerState, playerViewModel::onEvent)
                        (autoLoginStatus as? AutoLoginStatus.Retrying)?.let { status ->
                            ServerUnreachableButton(status.message, onRetry = session::retryConnection)
                        }
                    }
                }
            },
            bottomBar = {
                if (currentScreen !is Screen.Login && currentScreen !is Screen.Player) {
                    Column {
                        if (hasSong) {
                            MiniPlayer(
                                viewModel = playerViewModel,
                                onArtistClick = { name ->
                                    navigator.push(Screen.ArtistAlbums(playerState.playback.currentSong?.youTubeArtistId(name), name))
                                },
                                onAlbumClick = { navigator.push(Screen.AlbumSongs(it)) },
                                onClick = { navigator.push(Screen.Player) },
                                onLyricsClick = { navigator.push(Screen.Player) },
                                onQueueClick = { tab -> toggleQueue(navigator, tab) },
                                openQueueTab = (currentScreen as? Screen.Queue)?.tab,
                            )
                        } else {
                            Box(Modifier.height(66.dp))
                        }
                        if (selectionBar != null) {
                            SelectionActionBar(selectionBar)
                        } else {
                            BottomNavigationBar(currentScreen, navigator)
                        }
                    }
                }
            },
        ) { padding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .onKeyEvent { event ->
                        if (event.type == KeyEventType.KeyUp) {
                            when (event.key) {
                                Key.MediaPlayPause -> {
                                    playerViewModel.onEvent(PlayerEvent.PlayPause)
                                    true
                                }
                                Key.MediaNext -> {
                                    playerViewModel.onEvent(PlayerEvent.Next)
                                    true
                                }
                                Key.MediaPrevious -> {
                                    playerViewModel.onEvent(PlayerEvent.Previous)
                                    true
                                }
                                else -> false
                            }
                        } else false
                    },
            ) {
            Row(Modifier.fillMaxSize()) {
                // Desktop: the library has the sidebar built in; every other screen gets it next to it
                val showSidebar = !isMobile && currentScreen !is Screen.Login &&
                    currentScreen !is Screen.Library && currentScreen !is Screen.Player
                if (showSidebar) {
                    DesktopLibrarySidebar(
                        viewModel = libraryViewModel,
                        onOpen = { navigator.push(Screen.Library(it)) },
                        modifier = Modifier
                            .padding(start = 16.dp, top = 16.dp, bottom = 16.dp)
                            .width(280.dp)
                            .fillMaxHeight(),
                    )
                }
            // Only touch screens: a mouse wheel scrolled up at the top would pull too
            val canRefresh = isMobile && currentScreen !is Screen.Login && currentScreen !is Screen.Player &&
                currentScreen !is Screen.Queue
            PullToRefreshBox(
                isRefreshing = refreshing,
                onRefresh = startRefresh,
                enabled = canRefresh,
                modifier = Modifier.weight(1f).fillMaxHeight(),
            ) {
                // Each visit of a screen starts afresh, apart from how far it was scrolled when going back or forward to it
                CompositionLocalProvider(LocalScrollPositions provides currentEntry.scrollPositions) {
                key(currentEntry.id, screenGeneration) {
                when (val screen = currentScreen) {
                    is Screen.Login -> {
                        val vm = remember { LoginViewModel(connection, settingsHolder.settings.value) }
                        LaunchedEffect(vm) {
                            vm.effects.collectLatest { effect ->
                                when (effect) {
                                    LoginEffect.NavigateToLibrary -> navigator.replaceAll(Screen.Library())
                                }
                            }
                        }
                        LoginScreen(vm)
                    }

                    is Screen.Library -> {
                        LaunchedEffect(screen) {
                            val selection = screen.selection
                            if (selection != null) {
                                libraryViewModel.onEvent(LibraryEvent.Show(selection))
                            } else {
                                // Remember what the pane shows, so going back to this screen shows it again
                                val shown = libraryViewModel.state.mapNotNull { it.selection }.first()
                                navigator.replace(Screen.Library(shown))
                            }
                        }
                        LibraryScreen(
                            viewModel = libraryViewModel,
                            currentSongId = playerState.playback.currentSong?.id,
                            onOpen = { navigator.push(Screen.Library(it)) },
                        )
                    }

                    is Screen.ArtistAlbums -> {
                        val vm = remember(screen.artistId, screen.artistName) {
                            ArtistAlbumsViewModel(screen.artistId, screen.artistName, repository, youTube)
                        }
                        LaunchedEffect(vm) {
                            vm.effects.collectLatest { effect ->
                                when (effect) {
                                    is ArtistAlbumsEffect.NavigateToAlbum ->
                                        navigator.push(Screen.AlbumSongs(effect.albumId))
                                    is ArtistAlbumsEffect.PlaySongs ->
                                        playSongs(playerViewModel, effect.songs, effect.startIndex, effect.shuffle)
                                }
                            }
                        }
                        ArtistAlbumsScreen(
                            viewModel = vm,
                            onBack = { navigator.pop() },
                            currentSongId = playerState.playback.currentSong?.id
                        )
                    }

                    is Screen.AlbumSongs -> {
                        val vm = remember(screen.albumId) {
                            AlbumDetailViewModel(screen.albumId, repository, youTube)
                        }
                        LaunchedEffect(vm) {
                            vm.effects.collectLatest { effect ->
                                when (effect) {
                                    is AlbumDetailEffect.PlaySongs ->
                                        playSongs(playerViewModel, effect.songs, effect.startIndex, effect.shuffle)
                                    is AlbumDetailEffect.NavigateToArtist ->
                                        navigator.push(Screen.ArtistAlbums(effect.artistId, effect.artistName))
                                }
                            }
                        }
                        AlbumDetailScreen(
                            viewModel = vm,
                            onBack = { navigator.pop() },
                            currentSongId = playerState.playback.currentSong?.id
                        )
                    }

                    is Screen.PlaylistSongs -> {
                        val vm = remember(screen.playlistId) {
                            PlaylistDetailViewModel(screen.playlistId, repository, repository.playlistChanges)
                        }
                        LaunchedEffect(vm) {
                            vm.effects.collectLatest { effect ->
                                when (effect) {
                                    is PlaylistDetailEffect.PlaySongs ->
                                        playSongs(playerViewModel, effect.songs, effect.startIndex, effect.shuffle)
                                    is PlaylistDetailEffect.NavigateToArtist ->
                                        navigator.push(Screen.ArtistAlbums(null, effect.artistName))
                                    is PlaylistDetailEffect.NavigateToAlbum ->
                                        navigator.push(Screen.AlbumSongs(effect.albumId))
                                }
                            }
                        }
                        PlaylistDetailScreen(
                            viewModel = vm,
                            onBack = { navigator.pop() },
                            currentSongId = playerState.playback.currentSong?.id
                        )
                    }

                    is Screen.FavoriteSongs -> {
                        val libraryState by libraryViewModel.state.collectAsState()
                        FavoriteSongsScreen(
                            songs = libraryState.favoriteSongs,
                            onBack = { navigator.pop() },
                            onPlay = { songs, startIndex, shuffle -> playSongs(playerViewModel, songs, startIndex, shuffle) },
                            onArtistClick = { name -> navigator.push(Screen.ArtistAlbums(null, name)) },
                            onAlbumClick = { navigator.push(Screen.AlbumSongs(it)) },
                            currentSongId = playerState.playback.currentSong?.id,
                        )
                    }

                    is Screen.GenreSongs -> {
                        GenreSongsScreen(
                            genre = screen.genre,
                            repository = repository,
                            onBack = { navigator.pop() },
                            onPlay = { songs, startIndex, shuffle -> playSongs(playerViewModel, songs, startIndex, shuffle) },
                            onArtistClick = { name -> navigator.push(Screen.ArtistAlbums(null, name)) },
                            onAlbumClick = { navigator.push(Screen.AlbumSongs(it)) },
                            currentSongId = playerState.playback.currentSong?.id,
                        )
                    }

                    is Screen.Player -> {
                        PlayerScreen(
                            viewModel = playerViewModel,
                            isVideoFullscreen = showVideoFullscreen,
                            onToggleVideoFullscreen = { setVideoFullscreen(!videoFullscreen) },
                            onArtistClick = { name ->
                                navigator.push(Screen.ArtistAlbums(playerState.playback.currentSong?.youTubeArtistId(name), name))
                            },
                            onAlbumClick = { navigator.push(Screen.AlbumSongs(it)) },
                            onBack = { navigator.pop() },
                            onQueueClick = { tab -> navigator.push(Screen.Queue(tab)) },
                        )
                    }

                    is Screen.Queue -> {
                        QueueScreen(
                            viewModel = playerViewModel,
                            tab = screen.tab,
                            onTabSelected = { navigator.replace(Screen.Queue(it)) },
                            onArtistClick = { name -> navigator.push(Screen.ArtistAlbums(null, name)) },
                            onAlbumClick = { navigator.push(Screen.AlbumSongs(it)) },
                        )
                    }

                    is Screen.Search -> {
                        SearchScreen(
                            viewModel = searchViewModel,
                            onBack = { navigator.pop() },
                            currentSongId = playerState.playback.currentSong?.id
                        )
                    }

                    is Screen.Downloads -> {
                        DownloadsScreen(
                            youTube = youTube,
                            deviceDownloads = connection.downloads.takeIf { it.isSupported },
                            tab = screen.tab,
                            onTabSelected = { navigator.replace(Screen.Downloads(it)) },
                            onPlay = { songs, startIndex -> playSongs(playerViewModel, songs, startIndex, null) },
                            onArtistClick = { id, name -> navigator.push(Screen.ArtistAlbums(id, name)) },
                            onAlbumClick = { navigator.push(Screen.AlbumSongs(it)) },
                            onPlaylistClick = { navigator.push(Screen.PlaylistSongs(it)) },
                            currentSongId = playerState.playback.currentSong?.id,
                        )
                    }

                    is Screen.Settings -> {
                        val vm = remember { SettingsViewModel(connection, settingsHolder) }
                        LaunchedEffect(vm) {
                            vm.effects.collectLatest { effect ->
                                when (effect) {
                                    SettingsEffect.LoggedOut -> {
                                        playerViewModel.onEvent(PlayerEvent.Pause)
                                        navigator.replaceAll(Screen.Login)
                                    }
                                    is SettingsEffect.ShowMessage -> {}
                                }
                            }
                        }
                        SettingsScreen(vm) { navigator.pop() }
                    }
                }
                }
                }
            }
            }
            }
        }
        songDragAndDrop?.let { SongDragOverlay(it) }
        if (showVideoFullscreen) {
            val focusRequester = remember { FocusRequester() }
            LaunchedEffect(Unit) { focusRequester.requestFocus() }
            VideoPlayerView(
                player = playerViewModel.player,
                aspectRatio = playerState.videoAspectRatio,
                thumbnailUrl = coverArtUrlProvider.getUrl(playerState.playback.currentSong?.coverArtId),
                isBuffering = playerState.playback.status == PlaybackStatus.Buffering,
                isFullscreen = true,
                onToggleFullscreen = { setVideoFullscreen(false) },
                controls = { FullscreenVideoControls(playerViewModel) },
                modifier = Modifier
                    .fillMaxSize()
                    .focusRequester(focusRequester)
                    .focusable()
                    .onKeyEvent { event ->
                        if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
                            setVideoFullscreen(false)
                            true
                        } else false
                    },
            )
        }
        }
        }
    }
}

/** Shows [tab] of the queue screen, or leaves it if it's shown already. */
private fun toggleQueue(navigator: Navigator, tab: QueueTab) {
    val screen = navigator.currentScreen.value
    when {
        screen is Screen.Queue && screen.tab == tab -> navigator.pop()
        screen is Screen.Queue -> navigator.replace(Screen.Queue(tab))
        else -> navigator.push(Screen.Queue(tab))
    }
}

private fun playSongs(playerViewModel: PlayerViewModel, songs: List<Song>, startIndex: Int, shuffle: Boolean?) {
    playerViewModel.onEvent(PlayerEvent.PlaySongs(songs, startIndex, shuffle))
}

@Composable
private fun BottomNavigationBar(currentScreen: Screen, navigator: Navigator) {
    NavigationBar(
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        NavigationBarItem(
            selected = currentScreen is Screen.Library,
            onClick = {
                if (currentScreen !is Screen.Library) {
                    navigator.replaceAll(Screen.Library())
                }
            },
            icon = {
                Icon(Icons.Default.LibraryMusic, contentDescription = null, tint = if (currentScreen is Screen.Library) AccentColor else SpotifyLightGray)
            },
            label = {
                Text(
                    "Library",
                    color = if (currentScreen is Screen.Library) AccentColor else SpotifyLightGray,
                    fontWeight = if (currentScreen is Screen.Library) FontWeight.Bold else FontWeight.Normal,
                )
            },
            colors = NavigationBarItemDefaults.colors(
                indicatorColor = MaterialTheme.colorScheme.surfaceVariant,
            ),
        )

        NavigationBarItem(
            selected = currentScreen is Screen.Settings,
            onClick = {
                if (currentScreen !is Screen.Settings) {
                    navigator.push(Screen.Settings)
                }
            },
            icon = {
                Icon(Icons.Default.Settings, contentDescription = null, tint = if (currentScreen is Screen.Settings) AccentColor else SpotifyLightGray)
            },
            label = {
                Text(
                    "Settings",
                    color = if (currentScreen is Screen.Settings) AccentColor else SpotifyLightGray,
                    fontWeight = if (currentScreen is Screen.Settings) FontWeight.Bold else FontWeight.Normal,
                )
            },
            colors = NavigationBarItemDefaults.colors(
                indicatorColor = MaterialTheme.colorScheme.surfaceVariant,
            ),
        )
    }
}
