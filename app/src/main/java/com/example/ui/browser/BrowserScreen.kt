package com.example.ui.browser

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.outlined.CloudDone
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.engine.DefaultBrowserHelper
import com.example.engine.OfflineStorageUtil
import com.example.ui.theme.ApexMotion
import com.example.ui.theme.LocalReducedMotion
import com.example.ui.viewmodel.BrowserViewModel

@Composable
fun BrowserScreen(
    viewModel: BrowserViewModel,
    windowSizeClass: WindowSizeClass,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val preferences by viewModel.preferencesState.collectAsStateWithLifecycle()
    val bookmarks by viewModel.bookmarksState.collectAsStateWithLifecycle()
    val searchHistory by viewModel.searchHistoryState.collectAsStateWithLifecycle()
    val offlinePages by viewModel.offlinePagesState.collectAsStateWithLifecycle()
    val offlinePagesCount by viewModel.offlinePagesCountState.collectAsStateWithLifecycle()
    val topVisits by viewModel.topVisitsState.collectAsStateWithLifecycle()
    val browsingHistory by viewModel.browsingHistoryState.collectAsStateWithLifecycle()
    val browsingHistoryCount by viewModel.browsingHistoryCountState.collectAsStateWithLifecycle()
    val downloads by viewModel.downloadsState.collectAsStateWithLifecycle()
    val downloadsCount by viewModel.downloadsCountState.collectAsStateWithLifecycle()
    val savedPasswords by viewModel.savedPasswordsState.collectAsStateWithLifecycle()
    val savedPasswordsCount by viewModel.savedPasswordsCountState.collectAsStateWithLifecycle()

    val reducedMotion = LocalReducedMotion.current
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val snackbarHostState = remember { SnackbarHostState() }
    val viewBookmarksLabel = stringResource(R.string.action_view_bookmarks)
    val viewDownloadsLabel = stringResource(R.string.menu_downloads)

    var defaultBrowserStatus by remember {
        mutableStateOf(DefaultBrowserHelper.getStatus(context))
    }

    val defaultBrowserLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        defaultBrowserStatus = DefaultBrowserHelper.getStatus(context)
    }

    DisposableEffect(lifecycleOwner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                defaultBrowserStatus = DefaultBrowserHelper.getStatus(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    LaunchedEffect(uiState.isMenuSheetOpen, uiState.isSettingsScreenOpen) {
        defaultBrowserStatus = DefaultBrowserHelper.getStatus(context)
    }

    val isWideScreen = windowSizeClass.widthSizeClass != WindowWidthSizeClass.Compact

    val activeTab = uiState.activeTab
    val canNavigateBack = !activeTab.isHomePage || activeTab.canGoBack
    val isCurrentWebPageBookmarked = remember(activeTab.url, bookmarks) {
        if (activeTab.isHomePage) {
            false
        } else {
            val activeNormalized = activeTab.url.trim().substringBefore('#').trimEnd('/')
            bookmarks.any { bookmark ->
                bookmark.url.trim().equals(activeTab.url.trim(), ignoreCase = true) ||
                    bookmark.url.trim().substringBefore('#').trimEnd('/')
                        .equals(activeNormalized, ignoreCase = true)
            }
        }
    }

    LaunchedEffect(uiState.snackbarNotice) {
        val notice = uiState.snackbarNotice ?: return@LaunchedEffect
        val isBookmarkNotice = notice.startsWith("Bookmarked") || notice.startsWith("Removed bookmark")
        val isDownloadNotice = notice.startsWith("Downloading") || notice.startsWith("Downloaded")
        val result = snackbarHostState.showSnackbar(
            message = notice,
            actionLabel = when {
                isBookmarkNotice -> viewBookmarksLabel
                isDownloadNotice -> viewDownloadsLabel
                else -> null
            },
            duration = SnackbarDuration.Short
        )
        viewModel.consumeSnackbarNotice()
        if (result == SnackbarResult.ActionPerformed) {
            if (isBookmarkNotice) {
                viewModel.setBookmarksScreenOpen(true)
            } else if (isDownloadNotice) {
                viewModel.setDownloadsScreenOpen(true)
            }
        }
    }

    BackHandler(
        enabled = canNavigateBack &&
            !uiState.isTabSwitcherOpen &&
            !uiState.isBookmarksScreenOpen &&
            !uiState.isHistoryScreenOpen &&
            !uiState.isDownloadsScreenOpen &&
            !uiState.isOfflinePagesScreenOpen &&
            !uiState.isPasswordManagerOpen &&
            !uiState.isSettingsScreenOpen &&
            !uiState.isSearchScreenOpen &&
            !uiState.isMenuSheetOpen
    ) {
        viewModel.navigateBack()
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        topBar = {
            if (!uiState.isSearchScreenOpen &&
                !uiState.isSettingsScreenOpen &&
                !uiState.isBookmarksScreenOpen &&
                !uiState.isHistoryScreenOpen &&
                !uiState.isDownloadsScreenOpen &&
                !uiState.isOfflinePagesScreenOpen &&
                !uiState.isPasswordManagerOpen &&
                !uiState.isTabSwitcherOpen
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    CompactTopToolbar(
                        visible = uiState.isToolbarVisible || activeTab.isHomePage,
                        activeTab = activeTab,
                        activeTabIndex = uiState.activeTabIndex,
                        totalTabs = uiState.tabs.size,
                        onAddressPillClick = {
                            viewModel.openSearchScreen(prefillCurrentUrl = !activeTab.isHomePage)
                        },
                        onReloadClick = viewModel::reloadActiveTab,
                        onSwipeToNextTab = viewModel::selectNextTab,
                        onSwipeToPreviousTab = viewModel::selectPreviousTab,
                        searchEngine = preferences.searchEngine
                    )

                    if (activeTab.isOfflineCopy) {
                        Surface(
                            color = MaterialTheme.colorScheme.tertiaryContainer,
                            contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("offline_copy_banner")
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 6.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.CloudDone,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Text(
                                        text = "Offline Mode • Saved ${OfflineStorageUtil.formatSavedTimestamp(activeTab.offlineSavedAt ?: 0L)}",
                                        style = MaterialTheme.typography.labelMedium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                TextButton(
                                    onClick = { viewModel.reloadOnline(activeTab.id) },
                                    modifier = Modifier.testTag("offline_banner_reload_online")
                                ) {
                                    Text(
                                        text = stringResource(R.string.offline_banner_try_online),
                                        style = MaterialTheme.typography.labelMedium
                                    )
                                }
                            }
                        }
                    }

                    if (isWideScreen) {
                        ExpressiveHorizontalTabStrip(
                            tabs = uiState.tabs,
                            activeTabId = uiState.activeTabId,
                            onSelectTab = viewModel::selectTab,
                            onCloseTab = viewModel::closeTab,
                            onNewTab = { viewModel.createNewTab() }
                        )
                    }
                }
            }
        },
        bottomBar = {
            if (!uiState.isTabSwitcherOpen &&
                !uiState.isSearchScreenOpen &&
                !uiState.isSettingsScreenOpen &&
                !uiState.isBookmarksScreenOpen &&
                !uiState.isHistoryScreenOpen &&
                !uiState.isDownloadsScreenOpen &&
                !uiState.isOfflinePagesScreenOpen &&
                !uiState.isPasswordManagerOpen
            ) {
                val activeGroup = uiState.activeTabGroup
                val showGroupStrip = activeGroup != null

                Column(modifier = Modifier.fillMaxWidth()) {
                    AnimatedVisibility(
                        visible = showGroupStrip,
                        enter = if (reducedMotion) {
                            fadeIn()
                        } else {
                            expandVertically(animationSpec = ApexMotion.spatialSpring()) +
                                fadeIn(animationSpec = ApexMotion.effectsSpring())
                        },
                        exit = if (reducedMotion) {
                            fadeOut()
                        } else {
                            shrinkVertically(animationSpec = ApexMotion.spatialSpring()) +
                                fadeOut(animationSpec = ApexMotion.effectsSpring())
                        }
                    ) {
                        if (activeGroup != null) {
                            BottomGroupTabStrip(
                                group = activeGroup,
                                groupTabs = uiState.activeGroupTabs,
                                activeTabId = uiState.activeTabId,
                                onSelectTab = viewModel::selectTab,
                                onCloseTab = viewModel::closeTab,
                                onAddTabToGroup = {
                                    viewModel.createNewTab(
                                        groupId = activeGroup.id,
                                        stayInSwitcher = false
                                    )
                                },
                                onEditGroup = { viewModel.openGroupEditor(activeGroup.id) }
                            )
                        }
                    }

                    BraveBottomNavigationBar(
                        isHomeActive = activeTab.isHomePage,
                        isBookmarksScreenOpen = uiState.isBookmarksScreenOpen,
                        isSettingsScreenOpen = uiState.isSettingsScreenOpen,
                        isMenuSheetOpen = uiState.isMenuSheetOpen,
                        isCurrentWebPageBookmarked = isCurrentWebPageBookmarked,
                        isTabSwitcherOpen = uiState.isTabSwitcherOpen,
                        isSearchScreenOpen = uiState.isSearchScreenOpen,
                        openTabCount = uiState.tabs.size,
                        onHomeClick = viewModel::onBottomHomePressed,
                        onBookmarksClick = viewModel::onBottomBookmarksPressed,
                        onSearchClick = {
                            if (uiState.isSearchScreenOpen) {
                                viewModel.closeSearchScreen()
                            } else {
                                viewModel.openSearchScreen(prefillCurrentUrl = false)
                            }
                        },
                        onTabsClick = { viewModel.setTabSwitcherOpen(!uiState.isTabSwitcherOpen) },
                        onMenuClick = viewModel::toggleMenuSheet
                    )
                }
            }
        },
        modifier = modifier.fillMaxSize()
    ) { innerPadding ->
        val (enterOverlay, exitOverlay) = ApexMotion.containerTransformSpec(reducedMotion)

        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            Row(modifier = Modifier.fillMaxSize()) {
                // Main Browser Content Area with Shared-Axis Tab Switching
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                ) {
                    val forwardSwitch = uiState.activeTabIndex >= uiState.previousTabIndex
                    AnimatedContent(
                        targetState = activeTab,
                        contentKey = { it.id },
                        transitionSpec = {
                            ApexMotion.sharedAxisTabTransition(
                                forward = forwardSwitch,
                                reducedMotion = reducedMotion
                            )
                        },
                        label = "SharedAxisTabContent"
                    ) { currentTab ->
                        if (currentTab.isHomePage) {
                            StartPageHome(
                                preferences = preferences,
                                topVisits = topVisits,
                                onOpenSearchScreen = {
                                    viewModel.openSearchScreen(prefillCurrentUrl = false)
                                },
                                onSelectDestination = { url ->
                                    viewModel.submitNavigation(url, currentTab.id)
                                },
                                onAddTopVisit = viewModel::addTopVisit,
                                onDeleteTopVisit = viewModel::deleteTopVisit,
                                offlinePagesCount = offlinePagesCount,
                                onOpenOfflinePagesScreen = {
                                    viewModel.setOfflinePagesScreenOpen(true)
                                },
                                isIncognito = currentTab.isPrivate,
                                incognitoTabsCount = uiState.incognitoTabCount,
                                onCloseAllIncognitoTabs = viewModel::closeAllIncognitoTabs
                            )
                        } else {
                            WebViewViewport(
                                tab = currentTab,
                                preferences = preferences,
                                privacyManager = viewModel.privacyManager,
                                performanceOptimizer = viewModel.performanceOptimizer,
                                existingWebView = viewModel.getWebView(currentTab.id),
                                onRegisterWebView = viewModel::registerWebView,
                                onPageStarted = viewModel::onWebViewPageStarted,
                                onProgressChanged = viewModel::onWebViewProgress,
                                onPageFinished = viewModel::onWebViewPageFinished,
                                onSubresourceBlocked = viewModel::onSubresourceBlocked,
                                onHttpsUpgradeTriggered = { _ ->
                                    viewModel.recordHttpsUpgradeStat()
                                },
                                onScrollDelta = viewModel::onWebViewScrollDelta,
                                onPageLoadFailed = { tabId, failedUrl ->
                                    viewModel.onPageLoadFailed(tabId, failedUrl)
                                },
                                onRefreshPage = viewModel::reloadActiveTab,
                                onOpenInNewTab = { url ->
                                    viewModel.openLinkInNewTab(url, inGroup = false, isPrivate = false)
                                },
                                onOpenInNewTabInGroup = { url ->
                                    viewModel.openLinkInNewTab(url, inGroup = true, isPrivate = false)
                                },
                                onOpenInPrivateTab = { url ->
                                    viewModel.openLinkInNewTab(url, inGroup = false, isPrivate = true)
                                },
                                onOpenInNewWindow = { url ->
                                    viewModel.openLinkInNewTab(url, inGroup = false, isPrivate = false, switchToTab = true)
                                },
                                onAddToReadingList = { title, url ->
                                    viewModel.addLinkToReadingList(title, url)
                                },
                                onDownloadFile = { url, fileName, contentDisposition, mimeType, contentLength, userAgent ->
                                    viewModel.startFastDownload(
                                        url = url,
                                        suggestedFileName = fileName,
                                        contentDisposition = contentDisposition,
                                        mimeType = mimeType,
                                        contentLength = contentLength,
                                        userAgent = userAgent
                                    )
                                },
                                onCredentialSubmitted = viewModel::onWebCredentialSubmitted
                            )
                        }
                    }
                }
            }

            // Live 1-Tap Password Autofill Bar when visiting sites with saved credentials
            val showAutofillBar = !activeTab.isHomePage &&
                !activeTab.isOfflineCopy &&
                preferences.passwordAutofillEnabled &&
                uiState.matchedCredentialsForActiveTab.isNotEmpty() &&
                !uiState.isSearchScreenOpen &&
                !uiState.isTabSwitcherOpen &&
                !uiState.isPasswordManagerOpen &&
                !uiState.isSettingsScreenOpen &&
                !uiState.isBookmarksScreenOpen &&
                !uiState.isHistoryScreenOpen &&
                !uiState.isDownloadsScreenOpen &&
                !uiState.isOfflinePagesScreenOpen &&
                !uiState.isMenuSheetOpen &&
                uiState.pendingCredentialPrompt == null

            AnimatedVisibility(
                visible = showAutofillBar,
                enter = if (reducedMotion) fadeIn() else expandVertically(ApexMotion.spatialSpring()) + fadeIn(),
                exit = if (reducedMotion) fadeOut() else shrinkVertically(ApexMotion.spatialSpring()) + fadeOut(),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = 12.dp, vertical = 10.dp)
            ) {
                Surface(
                    shape = MaterialTheme.shapes.extraLarge,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    tonalElevation = 6.dp,
                    shadowElevation = 6.dp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("password_autofill_bar")
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Key,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = stringResource(R.string.passwords_fill_action),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        uiState.matchedCredentialsForActiveTab.forEach { credential ->
                            AssistChip(
                                onClick = { viewModel.fillCredentialIntoActiveWebView(credential) },
                                label = {
                                    Text(
                                        text = credential.username,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Outlined.Key,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp)
                                    )
                                },
                                colors = AssistChipDefaults.assistChipColors(
                                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                                    labelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                    leadingIconContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                                ),
                                modifier = Modifier.testTag("password_autofill_chip_${credential.id}")
                            )
                        }
                    }
                }
            }

            // Live Credential Capture Prompt ("Save / Update Password?")
            val pendingCredential = uiState.pendingCredentialPrompt
            AnimatedVisibility(
                visible = pendingCredential != null && !uiState.isPasswordManagerOpen,
                enter = if (reducedMotion) fadeIn() else expandVertically(ApexMotion.spatialSpring()) + fadeIn(),
                exit = if (reducedMotion) fadeOut() else shrinkVertically(ApexMotion.spatialSpring()) + fadeOut(),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(horizontal = 14.dp, vertical = 10.dp)
            ) {
                if (pendingCredential != null) {
                    ElevatedCard(
                        shape = MaterialTheme.shapes.extraLarge,
                        colors = CardDefaults.elevatedCardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                        ),
                        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 8.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("password_save_prompt_card")
                    ) {
                        Column(
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 14.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Surface(
                                    shape = MaterialTheme.shapes.medium,
                                    color = MaterialTheme.colorScheme.primaryContainer,
                                    modifier = Modifier.size(40.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            imageVector = Icons.Filled.Key,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                }
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = stringResource(
                                            if (pendingCredential.existingId != null) {
                                                R.string.passwords_update_prompt_title
                                            } else {
                                                R.string.passwords_save_prompt_title
                                            }
                                        ),
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = "${pendingCredential.siteName} • ${pendingCredential.username}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                IconButton(
                                    onClick = viewModel::dismissPendingCredentialPrompt,
                                    modifier = Modifier
                                        .minimumInteractiveComponentSize()
                                        .testTag("password_save_prompt_close")
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.Close,
                                        contentDescription = stringResource(R.string.cancel)
                                    )
                                }
                            }
                            Row(
                                horizontalArrangement = Arrangement.End,
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                TextButton(
                                    onClick = viewModel::dismissPendingCredentialPrompt,
                                    modifier = Modifier.testTag("password_save_prompt_never")
                                ) {
                                    Text(stringResource(R.string.cancel))
                                }
                                Spacer(modifier = Modifier.width(8.dp))
                                Button(
                                    onClick = viewModel::confirmSavePendingCredential,
                                    modifier = Modifier.testTag("password_save_prompt_confirm")
                                ) {
                                    Text(
                                        text = if (pendingCredential.existingId != null) "Update" else "Save"
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Dedicated Search Screen
            AnimatedVisibility(
                visible = uiState.isSearchScreenOpen,
                enter = fadeIn(tween(140)),
                exit = fadeOut(tween(120)),
                modifier = Modifier.fillMaxSize()
            ) {
                SearchScreenOverlay(
                    query = uiState.searchQuery,
                    selectedEngine = preferences.searchEngine,
                    suggestions = uiState.searchSuggestions,
                    isLoadingSuggestions = uiState.isLoadingSuggestions,
                    recentHistory = searchHistory,
                    activeTabUrl = if (!activeTab.isHomePage) activeTab.url else null,
                    activeTabTitle = if (!activeTab.isHomePage) activeTab.title else null,
                    onQueryChange = viewModel::onSearchQueryChanged,
                    onSubmitSearch = { submitted -> viewModel.submitNavigation(submitted) },
                    onFillQuery = viewModel::onSearchQueryChanged,
                    onDeleteHistoryItem = viewModel::deleteSearchHistoryItem,
                    onClearAllHistory = viewModel::clearAllSearchHistory,
                    onDismiss = viewModel::closeSearchScreen
                )
            }

            // Settings Screen Overlay (Opened from Hamburger Menu Bottom Sheet)
            AnimatedVisibility(
                visible = uiState.isSettingsScreenOpen,
                enter = if (reducedMotion) fadeIn() else enterOverlay,
                exit = if (reducedMotion) fadeOut() else exitOverlay,
                modifier = Modifier.fillMaxSize()
            ) {
                SettingsScreenOverlay(
                    preferences = preferences,
                    onSelectSearchEngine = viewModel::selectSearchEngine,
                    onToggleShields = viewModel::toggleShields,
                    onToggleHttpsUpgrade = viewModel::toggleHttpsUpgrade,
                    onToggleScriptGuard = viewModel::toggleScriptGuard,
                    onToggleSmartPreload = viewModel::toggleAggressivePreload,
                    onToggleDesktopViewport = viewModel::toggleDesktopViewport,
                    onToggleDynamicColor = viewModel::toggleDynamicColor,
                    onSelectSeedPalette = viewModel::selectSeedPalette,
                    onClearBrowsingData = viewModel::resetPrivacyStats,
                    onToggleAutoSaveOfflinePages = viewModel::setAutoSaveOfflinePagesEnabled,
                    onToggleShowTopVisitsOnHome = viewModel::setShowTopVisitsOnHome,
                    onClearOfflinePages = viewModel::clearAllOfflinePages,
                    onOpenPasswordManager = { viewModel.setPasswordManagerOpen(true) },
                    onTogglePasswordAutofill = viewModel::togglePasswordAutofill,
                    defaultBrowserStatus = defaultBrowserStatus,
                    onOpenDefaultBrowserSettings = {
                        runCatching {
                            defaultBrowserLauncher.launch(
                                DefaultBrowserHelper.createRequestOrSettingsIntent(
                                    context = context,
                                    preferSystemSettingsWhenDefault = true
                                )
                            )
                        }
                    },
                    onDismiss = { viewModel.setSettingsScreenOpen(false) }
                )
            }

            // Offline Pages Screen Overlay
            AnimatedVisibility(
                visible = uiState.isOfflinePagesScreenOpen,
                enter = if (reducedMotion) fadeIn() else enterOverlay,
                exit = if (reducedMotion) fadeOut() else exitOverlay,
                modifier = Modifier.fillMaxSize()
            ) {
                OfflinePagesScreenOverlay(
                    offlinePages = offlinePages,
                    onSelectOfflinePage = { url -> viewModel.loadOfflinePage(url) },
                    onDeleteOfflinePage = { id -> viewModel.deleteOfflinePage(id) },
                    onClearAllOfflinePages = viewModel::clearAllOfflinePages,
                    onDismiss = { viewModel.setOfflinePagesScreenOpen(false) }
                )
            }

            // Bookmarks Screen Overlay
            AnimatedVisibility(
                visible = uiState.isBookmarksScreenOpen,
                enter = if (reducedMotion) fadeIn() else enterOverlay,
                exit = if (reducedMotion) fadeOut() else exitOverlay,
                modifier = Modifier.fillMaxSize()
            ) {
                BookmarksScreenOverlay(
                    bookmarks = bookmarks,
                    onSelectBookmark = { url -> viewModel.submitNavigation(url) },
                    onDeleteBookmark = viewModel::deleteBookmark,
                    onImportBookmarks = viewModel::importBookmarksFromText,
                    onNotifyExported = viewModel::notifyBookmarksExported,
                    onDismiss = { viewModel.setBookmarksScreenOpen(false) }
                )
            }

            // Dedicated Browsing History Screen Overlay
            AnimatedVisibility(
                visible = uiState.isHistoryScreenOpen,
                enter = if (reducedMotion) fadeIn() else enterOverlay,
                exit = if (reducedMotion) fadeOut() else exitOverlay,
                modifier = Modifier.fillMaxSize()
            ) {
                HistoryScreenOverlay(
                    historyItems = browsingHistory,
                    onSelectHistoryUrl = { url -> viewModel.submitNavigation(url) },
                    onOpenInNewTab = { url ->
                        viewModel.openLinkInNewTab(url, inGroup = false, isPrivate = false, switchToTab = true)
                    },
                    onOpenInPrivateTab = { url ->
                        viewModel.openLinkInNewTab(url, inGroup = false, isPrivate = true, switchToTab = true)
                    },
                    onBookmarkPage = { title, url ->
                        viewModel.addLinkToReadingList(title, url)
                    },
                    onDeleteHistoryItem = viewModel::deleteBrowsingHistoryItem,
                    onClearHistoryRange = viewModel::clearBrowsingHistoryRange,
                    onDismiss = { viewModel.setHistoryScreenOpen(false) }
                )
            }

            // Dedicated Fast Downloads Screen Overlay
            AnimatedVisibility(
                visible = uiState.isDownloadsScreenOpen,
                enter = if (reducedMotion) fadeIn() else enterOverlay,
                exit = if (reducedMotion) fadeOut() else exitOverlay,
                modifier = Modifier.fillMaxSize()
            ) {
                DownloadsScreenOverlay(
                    downloads = downloads,
                    onStartNewDownload = { url, customFileName ->
                        viewModel.startFastDownload(
                            url = url,
                            suggestedFileName = customFileName
                        )
                    },
                    onPauseDownload = viewModel::pauseDownload,
                    onResumeDownload = viewModel::resumeDownload,
                    onRetryDownload = viewModel::retryDownload,
                    onCancelDownload = viewModel::cancelDownload,
                    onDeleteDownload = viewModel::deleteDownload,
                    onClearCompletedDownloads = viewModel::clearCompletedDownloads,
                    onClearAllDownloads = viewModel::clearAllDownloads,
                    onOpenUrlInBrowser = { sourceUrl ->
                        viewModel.submitNavigation(sourceUrl)
                    },
                    onDismiss = { viewModel.setDownloadsScreenOpen(false) }
                )
            }

            // Dedicated Material 3 Expressive Password Manager Overlay
            AnimatedVisibility(
                visible = uiState.isPasswordManagerOpen,
                enter = if (reducedMotion) fadeIn() else enterOverlay,
                exit = if (reducedMotion) fadeOut() else exitOverlay,
                modifier = Modifier.fillMaxSize()
            ) {
                PasswordManagerScreenOverlay(
                    passwords = savedPasswords,
                    autofillEnabled = preferences.passwordAutofillEnabled,
                    decryptPassword = viewModel::decryptPassword,
                    onSavePassword = viewModel::saveOrUpdatePassword,
                    onToggleFavorite = viewModel::togglePasswordFavorite,
                    onDeletePassword = viewModel::deleteSavedPassword,
                    onClearVault = viewModel::clearAllSavedPasswords,
                    onImportCsv = viewModel::importPasswordsFromCsv,
                    onExportCsv = viewModel::exportPasswordsToCsv,
                    onToggleAutofill = viewModel::togglePasswordAutofill,
                    onOpenWebsite = { siteUrl ->
                        viewModel.submitNavigation(siteUrl)
                    },
                    onDismiss = { viewModel.setPasswordManagerOpen(false) }
                )
            }

            // Draggable Grid Tab Switcher Overlay (No Bottom Nav — Centered Pills)
            AnimatedVisibility(
                visible = uiState.isTabSwitcherOpen,
                enter = if (reducedMotion) fadeIn() else enterOverlay,
                exit = if (reducedMotion) fadeOut() else exitOverlay,
                modifier = Modifier.fillMaxSize()
            ) {
                DraggableTabGridOverlay(
                    gridEntries = uiState.gridEntries,
                    totalOpenTabs = uiState.tabs.size,
                    activeTabId = uiState.activeTabId,
                    expandedGroupId = uiState.expandedGroupId,
                    isIncognitoAvailable = uiState.isIncognitoUnlocked || uiState.incognitoTabCount > 0,
                    isActiveTabIncognito = activeTab.isPrivate,
                    onSelectTab = viewModel::selectTab,
                    onCloseTab = viewModel::closeTab,
                    onCloseGroup = viewModel::closeGroup,
                    onEditGroup = viewModel::openGroupEditor,
                    onMergeEntries = viewModel::mergeGridEntriesIntoGroup,
                    onMoveGroupedTabToTarget = viewModel::moveGroupedTabToGridTarget,
                    onOpenGroup = viewModel::setExpandedGroup,
                    onAddTabToGroup = { grpId -> viewModel.createNewTab(groupId = grpId) },
                    onUngroupTab = viewModel::ungroupTab,
                    onCycleTabColor = viewModel::cycleTabColor,
                    onNewTab = { viewModel.createNewTab() },
                    onNewIncognitoTab = { viewModel.createNewIncognitoTab() },
                    onCreateGroup = viewModel::createNewTabGroupFromSwitcher,
                    onResetAllTabs = viewModel::resetAllTabs,
                    onCloseAllIncognitoTabs = viewModel::closeAllIncognitoTabs,
                    onClearBrowsingData = viewModel::resetPrivacyStats,
                    onOpenSettings = { viewModel.setSettingsScreenOpen(true) },
                    onCloseSelectedTabs = viewModel::closeSelectedTabs,
                    onGroupSelectedTabs = viewModel::groupSelectedTabs,
                    onDismiss = { viewModel.setTabSwitcherOpen(false) }
                )
            }

            // Hamburger Menu ModalBottomSheet (contains Settings & Quick Browser Actions)
            if (uiState.isMenuSheetOpen) {
                BrowserMenuBottomSheet(
                    isHomeActive = activeTab.isHomePage,
                    desktopViewportEnabled = preferences.desktopViewportEnabled,
                    savedOfflinePagesCount = offlinePagesCount,
                    browsingHistoryCount = browsingHistoryCount,
                    downloadsCount = downloadsCount,
                    savedPasswordsCount = savedPasswordsCount,
                    isDefaultBrowser = defaultBrowserStatus.isApexDefault,
                    onOpenSettings = { viewModel.setSettingsScreenOpen(true) },
                    onSetAsDefaultBrowserClick = {
                        runCatching {
                            defaultBrowserLauncher.launch(
                                DefaultBrowserHelper.createRequestOrSettingsIntent(
                                    context = context,
                                    preferSystemSettingsWhenDefault = false
                                )
                            )
                        }
                    },
                    onNewTabClick = { viewModel.createNewTab() },
                    onNewIncognitoTabClick = { viewModel.createNewIncognitoTab() },
                    onOpenBookmarksClick = { viewModel.setBookmarksScreenOpen(true) },
                    onOpenHistoryClick = { viewModel.setHistoryScreenOpen(true) },
                    onOpenDownloadsClick = { viewModel.setDownloadsScreenOpen(true) },
                    onOpenPasswordsClick = { viewModel.setPasswordManagerOpen(true) },
                    onSaveOfflineClick = viewModel::saveCurrentPageForOffline,
                    onOpenOfflinePagesClick = { viewModel.setOfflinePagesScreenOpen(true) },
                    onReloadPageClick = viewModel::reloadActiveTab,
                    onToggleDesktopMode = viewModel::toggleDesktopViewport,
                    onClearBrowsingData = viewModel::resetPrivacyStats,
                    onDismiss = { viewModel.setMenuSheetOpen(false) }
                )
            }

            // Group Rename & Tonal Color Picker Dialog
            val groupToEdit = uiState.editingGroup
            if (groupToEdit != null) {
                GroupRenameColorDialog(
                    group = groupToEdit,
                    onSave = { newTitle, selectedColor ->
                        viewModel.updateGroupTitleAndColor(
                            groupId = groupToEdit.id,
                            newTitle = newTitle,
                            colorOption = selectedColor
                        )
                    },
                    onUngroupAll = { viewModel.ungroupAllInGroup(groupToEdit.id) },
                    onDismiss = viewModel::dismissGroupEditor
                )
            }
        }
    }
}
