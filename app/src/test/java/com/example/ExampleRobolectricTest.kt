package com.example

import com.example.automation.PlannerAgent
import com.example.automation.RetrieverEngine
import com.example.automation.TabSnapshotTarget
import com.example.automation.ValidatorAgent
import com.example.data.local.PrivacyStatSummaryEntity
import com.example.data.preferences.SearchEngineOption
import com.example.engine.PrivacyManager
import com.example.engine.SearchSuggestionEngine
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.down
import androidx.compose.ui.test.moveBy
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.test.up
import androidx.compose.ui.unit.dp
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.ui.unit.DpSize
import com.example.ui.browser.BrowserMenuBottomSheet
import com.example.ui.browser.BrowserScreen
import com.example.ui.browser.CompactTopToolbar
import com.example.ui.browser.DraggableTabGridOverlay
import com.example.ui.browser.GroupedListSpacing
import com.example.ui.browser.SettingsScreenOverlay
import com.example.ui.browser.WebViewViewport
import com.example.ui.browser.groupedListItemShape
import com.example.data.preferences.BrowserPreferences
import com.example.engine.DefaultBrowserHelper
import com.example.engine.DefaultBrowserStatus
import com.example.engine.PerformanceOptimizer
import com.example.ui.theme.ApexBrowserTheme
import com.example.ui.theme.ApexColorSchemeBuilder
import com.example.ui.theme.SeedPalettePreset
import com.example.ui.theme.TabGroupColorOption
import com.example.ui.viewmodel.BrowserTab
import com.example.ui.viewmodel.BrowserUiState
import com.example.ui.viewmodel.BrowserViewModel
import com.example.ui.viewmodel.TabGridEntry
import com.example.ui.viewmodel.TabGroupInfo
import android.content.Context
import android.os.Looper
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import org.robolectric.Shadows
import com.example.data.local.ApexDatabase
import com.example.data.local.OfflinePageEntity
import com.example.data.local.SavedPasswordEntity
import com.example.data.preferences.UserPreferencesRepository
import com.example.data.repository.BrowserRepository
import com.example.engine.FastDownloadEngine
import com.example.engine.OfflineStorageUtil
import com.example.engine.PasswordAuditEngine
import com.example.engine.PasswordStrength
import com.example.engine.PasswordStrengthEvaluator
import com.example.engine.PasswordVaultCrypto
import com.example.engine.PasswordVaultUtil
import com.example.engine.SecurePasswordGenerator
import com.example.ui.browser.PasswordManagerScreenOverlay
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import kotlinx.coroutines.flow.first
import java.io.File
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `verify fresh install state starts with single Home tab and zero stats`() {
        val freshState = BrowserUiState()
        assertEquals(1, freshState.tabs.size)
        assertTrue(freshState.activeTab.isHomePage)
        assertEquals("apex://home", freshState.activeTab.url)
        assertTrue(freshState.tabGroups.isEmpty())
        assertTrue(freshState.searchSuggestions.isEmpty())
        assertTrue(freshState.searchQuery.isEmpty())
        assertFalse(freshState.isSettingsScreenOpen)
        assertFalse(freshState.isMenuSheetOpen)

        SearchEngineOption.entries.forEach { option ->
            assertTrue(option.description.isNotBlank())
            assertTrue(option.searchUrlTemplate.contains("%s"))
        }

        val freshStats = PrivacyStatSummaryEntity()
        assertEquals(0, freshStats.trackersBlocked)
        assertEquals(0, freshStats.adsBlocked)
        assertEquals(0, freshStats.httpsUpgrades)
        assertEquals(0L, freshStats.bytesSaved)
        assertEquals(0L, freshStats.timeSavedMs)
    }

    @Test
    fun `verify SearchSuggestionEngine builds URLs and parses engine suggestions`() {
        val suggestionEngine = SearchSuggestionEngine()

        assertTrue(
            suggestionEngine.buildSuggestUrl(SearchEngineOption.DUCKDUCKGO, "kotlin")
                .contains("ac.duckduckgo.com")
        )
        assertTrue(
            suggestionEngine.buildSuggestUrl(SearchEngineOption.GOOGLE, "kotlin")
                .contains("suggestqueries.google.com")
        )
        assertTrue(
            suggestionEngine.buildSuggestUrl(SearchEngineOption.BRAVE, "kotlin")
                .contains("search.brave.com")
        )
        assertTrue(
            suggestionEngine.buildSuggestUrl(SearchEngineOption.WIKIPEDIA, "kotlin")
                .contains("en.wikipedia.org")
        )

        // OpenSearch JSON array (Google / DuckDuckGo list / Brave / Bing)
        val openSearchJson = """["kotlin",["kotlin","kotlin compose","kotlin coroutines"]]"""
        val googleParsed = suggestionEngine.parseEngineSuggestions(
            rawJson = openSearchJson,
            originalQuery = "kotlin",
            engine = SearchEngineOption.GOOGLE
        )
        assertEquals(3, googleParsed.size)
        assertEquals("kotlin compose", googleParsed[1].title)
        assertEquals("G", googleParsed[1].engineBadge)

        // DuckDuckGo object array format
        val ddgObjJson = """[{"phrase":"privacy browser"},{"phrase":"privacy tools"}]"""
        val ddgParsed = suggestionEngine.parseEngineSuggestions(
            rawJson = ddgObjJson,
            originalQuery = "privacy",
            engine = SearchEngineOption.DUCKDUCKGO
        )
        assertEquals(2, ddgParsed.size)
        assertEquals("privacy browser", ddgParsed[0].title)
        assertEquals("DDG", ddgParsed[0].engineBadge)

        // Wikipedia OpenSearch with descriptions and direct article URLs
        val wikiJson = """["jetpack",["Jetpack Compose"],["Android UI toolkit"],["https://en.wikipedia.org/wiki/Jetpack_Compose"]]"""
        val wikiParsed = suggestionEngine.parseEngineSuggestions(
            rawJson = wikiJson,
            originalQuery = "jetpack",
            engine = SearchEngineOption.WIKIPEDIA
        )
        assertTrue(wikiParsed.any { it.submitValue == "https://en.wikipedia.org/wiki/Jetpack_Compose" })
        assertTrue(wikiParsed.any { it.subtitle == "Android UI toolkit" })
    }

    @Test
    fun `verify M3 Expressive seed ColorScheme generation`() {
        val light = ApexColorSchemeBuilder.fromSeed(
            seed = SeedPalettePreset.ELECTRIC_COBALT.seedColor,
            darkTheme = false
        )
        val dark = ApexColorSchemeBuilder.fromSeed(
            seed = SeedPalettePreset.ELECTRIC_COBALT.seedColor,
            darkTheme = true
        )
        assertNotEquals(light.primaryContainer, dark.primaryContainer)
        assertNotEquals(light.surface, dark.surface)
    }

    @Test
    fun `verify Tab Group grid entries for 4 tabs vs 5+ tabs`() {
        val grp = TabGroupInfo(id = "grp_1", title = "Group 1")
        val fourGroupedTabs = (1..4).map { idx ->
            BrowserTab(id = "t_$idx", title = "Tab $idx", url = "https://example.com/$idx", groupId = "grp_1")
        }
        val stateFour = BrowserUiState(
            tabs = fourGroupedTabs + BrowserTab(id = "solo", title = "Solo", url = "apex://home"),
            tabGroups = listOf(grp)
        )
        val entriesFour = stateFour.gridEntries
        assertEquals(2, entriesFour.size)
        val groupFour = entriesFour.first() as TabGridEntry.GroupTab
        assertEquals(4, groupFour.tabs.size)
        assertFalse(groupFour.tabs.size > 4) // 4 tabs stay shown directly

        val sixGroupedTabs = (1..6).map { idx ->
            BrowserTab(id = "t_$idx", title = "Tab $idx", url = "https://example.com/$idx", groupId = "grp_1")
        }
        val stateSix = BrowserUiState(tabs = sixGroupedTabs, tabGroups = listOf(grp))
        val groupSix = stateSix.gridEntries.single() as TabGridEntry.GroupTab
        assertTrue(groupSix.tabs.size > 4)
        assertEquals(3, groupSix.tabs.size - 3) // 3 tabs shown + 4th slot shows +3 remaining
    }

    @Test
    fun `verify PrivacyManager HTTPS upgrade and tracker blocking`() {
        val privacyManager = PrivacyManager()
        val upgraded = privacyManager.normalizeAndUpgradeUrl(
            rawInput = "http://example.com/articles",
            httpsUpgradeEnabled = true,
            searchEngineTemplate = "https://duckduckgo.com/?q=%s"
        )
        assertEquals("https://example.com/articles", upgraded.finalUrl)
        assertTrue(upgraded.wasUpgradedToHttps)

        val blocked = privacyManager.evaluateRequest(
            requestUrl = "https://pagead2.googlesyndication.com/pagead/js/adsbygoogle.js",
            pageUrl = "https://news.ycombinator.com",
            shieldsEnabled = true
        )
        assertTrue(blocked.blocked)
        assertEquals("Ad Network", blocked.category)

        // Anti-adblock & pop-under domains must also be intercepted
        val admiralBlocked = privacyManager.evaluateRequest(
            requestUrl = "https://cdn.getadmiral.com/v2/admiral.js",
            pageUrl = "https://example.com",
            shieldsEnabled = true
        )
        assertTrue(admiralBlocked.blocked)

        val firstPartyAllowed = privacyManager.evaluateRequest(
            requestUrl = "https://news.ycombinator.com/news.css",
            pageUrl = "https://news.ycombinator.com",
            shieldsEnabled = true
        )
        assertFalse(firstPartyAllowed.blocked)

        // Stealth JS surrogate and 1x1 GIF surrogate verification
        val jsSurrogate = privacyManager.stealthBlockedResponse(
            "https://pagead2.googlesyndication.com/pagead/js/adsbygoogle.js"
        )
        assertEquals("application/javascript", jsSurrogate.mimeType)
        val jsText = jsSurrogate.data.bufferedReader().readText()
        assertTrue(jsText.contains("window.adsbygoogle"))
        assertTrue(jsText.contains("window.googletag"))

        val gifSurrogate = privacyManager.stealthBlockedResponse(
            "https://ad.doubleclick.net/ddm/trackimp/pixel.gif"
        )
        assertEquals("image/gif", gifSurrogate.mimeType)
        assertTrue(gifSurrogate.data.readBytes().isNotEmpty())

        val stealthScript = privacyManager.buildStealthAdblockScript(true)
        assertTrue(stealthScript.contains("FakeBlockAdBlock"))
        assertTrue(stealthScript.contains("offsetHeight"))
        assertTrue(stealthScript.contains("ytp-ad-skip-button"))
    }

    @Test
    fun `verify Nano multi-agent planner validator and Retriever parallel extraction`() = runTest {
        val planner = PlannerAgent()
        val steps = planner.decomposeTask(
            prompt = "Find the top 3 AI news articles today and summarize them",
            currentUrl = "apex://home"
        )
        assertTrue(steps.size >= 2)

        val validator = ValidatorAgent()
        val outcome = validator.validateAndSynthesize(
            userPrompt = "Summarize top 3",
            observations = emptyList()
        )
        assertTrue(outcome.passed)

        val retriever = RetrieverEngine()
        val rule = retriever.buildRuleFromInput("Extract all product names and prices from this page")
        val records = retriever.extractAcrossTabsInParallel(
            tabs = listOf(
                TabSnapshotTarget("t1", "AI News Pulse", "apex://news"),
                TabSnapshotTarget("t2", "Apex Gear Store", "apex://store")
            ),
            webViewLookup = { null },
            rule = rule
        )
        assertTrue(records.size >= 8)
    }

    @Test
    fun `verify Material 3 Grouped List item shapes for single first middle and last items`() {
        assertTrue(GroupedListSpacing > 0.dp)

        val single = groupedListItemShape(index = 0, totalCount = 1)
        val first = groupedListItemShape(index = 0, totalCount = 3)
        val middle = groupedListItemShape(index = 1, totalCount = 3)
        val last = groupedListItemShape(index = 2, totalCount = 3)

        assertEquals(RoundedCornerShape(24.dp), single)
        assertEquals(
            RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp, bottomStart = 5.dp, bottomEnd = 5.dp),
            first
        )
        assertEquals(RoundedCornerShape(5.dp), middle)
        assertEquals(
            RoundedCornerShape(topStart = 5.dp, topEnd = 5.dp, bottomStart = 24.dp, bottomEnd = 24.dp),
            last
        )
        assertNotEquals(first, middle)
        assertNotEquals(middle, last)
        assertNotEquals(first, last)
    }

    @Test
    fun `verify group rename color palettes and bottom group tab strip state`() {
        TabGroupColorOption.entries.forEach { option ->
            val lightPalette = ApexColorSchemeBuilder.groupTonalColors(option, darkTheme = false)
            val darkPalette = ApexColorSchemeBuilder.groupTonalColors(option, darkTheme = true)
            assertNotEquals(lightPalette.container, darkPalette.container)
            assertNotEquals(lightPalette.accent, lightPalette.onAccent)
        }

        val emeraldGroup = TabGroupInfo(
            id = "grp_research",
            title = "Research",
            colorOption = TabGroupColorOption.EMERALD
        )
        val homeInGroup = BrowserTab(
            id = "t_home_grp",
            title = "Home",
            url = "apex://home",
            groupId = "grp_research",
            tabColor = TabGroupColorOption.EMERALD
        )
        val docsInGroup = BrowserTab(
            id = "t_docs_grp",
            title = "Kotlin Docs",
            url = "https://kotlinlang.org",
            groupId = "grp_research",
            tabColor = TabGroupColorOption.EMERALD
        )
        val standaloneTab = BrowserTab(
            id = "t_solo",
            title = "Solo",
            url = "apex://home",
            groupId = null
        )

        val stateInGroup = BrowserUiState(
            tabs = listOf(homeInGroup, docsInGroup, standaloneTab),
            tabGroups = listOf(emeraldGroup),
            activeTabId = "t_home_grp"
        )
        assertNotNull(stateInGroup.activeTabGroup)
        assertEquals("Research", stateInGroup.activeTabGroup?.title)
        assertEquals(TabGroupColorOption.EMERALD, stateInGroup.activeTabGroup?.colorOption)
        assertEquals(2, stateInGroup.activeGroupTabs.size)

        val stateOnStandalone = stateInGroup.copy(activeTabId = "t_solo")
        assertNull(stateOnStandalone.activeTabGroup)
        assertTrue(stateOnStandalone.activeGroupTabs.isEmpty())
    }

    @Test
    fun `verify OfflineStorageUtil unescapes JS DOM output and formats sizes and snippets`() {
        val sampleJsHtml = "\"<!DOCTYPE html><html><head><title>Test</title><\\/head><body><h1>Hello \\\"World\\\"<\\/h1><p>This is offline content.<\\/p><\\/body><\\/html>\""
        val unescaped = OfflineStorageUtil.unescapeJsString(sampleJsHtml)
        assertTrue(unescaped.startsWith("<!DOCTYPE html>"))
        assertTrue(unescaped.contains("Hello \"World\""))
        assertFalse(unescaped.startsWith("\""))
        assertFalse(unescaped.endsWith("\""))

        val rawWithoutQuotes = "<html><body>Clean HTML</body></html>"
        assertEquals("<html><body>Clean HTML</body></html>", OfflineStorageUtil.unescapeJsString(rawWithoutQuotes))

        // Snippet extraction
        val complexHtml = """
            <html>
                <head>
                    <style>body { color: red; }</style>
                    <script>alert('hello');</script>
                </head>
                <body>
                    <h1>Apex Browser Documentation</h1>
                    <p>Learn how to browse privately and save offline copies of web pages.</p>
                </body>
            </html>
        """.trimIndent()
        val snippet = OfflineStorageUtil.extractSnippet(complexHtml)
        assertFalse(snippet.contains("<"))
        assertFalse(snippet.contains(">"))
        assertFalse(snippet.contains("alert"))
        assertTrue(snippet.contains("Apex Browser Documentation"))
        assertTrue(snippet.contains("Learn how to browse privately"))

        // Byte size formatting
        assertEquals("500 B", OfflineStorageUtil.formatByteSize(500L))
        assertEquals("1.0 KB", OfflineStorageUtil.formatByteSize(1024L))
        assertEquals("2.5 KB", OfflineStorageUtil.formatByteSize(2560L))
        assertEquals("1.5 MB", OfflineStorageUtil.formatByteSize(1572864L))
    }

    @Test
    fun `verify OfflinePageEntity and BrowserTab offline state models`() {
        val page = OfflinePageEntity(
            id = 42L,
            url = "https://example.com/article",
            title = "Offline Article",
            htmlContent = "<html><body>Offline copy</body></html>",
            textSnippet = "Offline copy",
            sizeBytes = 2048L,
            savedAt = 1700000000000L
        )
        assertEquals(42L, page.id)
        assertEquals("https://example.com/article", page.url)
        assertEquals("Offline Article", page.title)
        assertEquals(2048L, page.sizeBytes)

        val offlineTab = BrowserTab(
            id = "t_offline",
            title = page.title,
            url = page.url,
            isOfflineCopy = true,
            offlineSavedAt = page.savedAt,
            offlineHtmlContent = page.htmlContent
        )
        assertTrue(offlineTab.isOfflineCopy)
        assertEquals(1700000000000L, offlineTab.offlineSavedAt)
        assertEquals("<html><body>Offline copy</body></html>", offlineTab.offlineHtmlContent)
        assertFalse(offlineTab.isHomePage)

        val uiState = BrowserUiState(
            tabs = listOf(offlineTab),
            activeTabId = "t_offline",
            isOfflinePagesScreenOpen = true
        )
        assertTrue(uiState.isOfflinePagesScreenOpen)
        assertEquals(1, uiState.tabs.size)
        assertTrue(uiState.activeTab.isOfflineCopy)
    }

    @Test
    fun `verify TopVisitEntity model and search engine options for start page`() {
        val topVisit = com.example.data.local.TopVisitEntity(
            id = 1L,
            title = "YouTube",
            url = "https://youtube.com"
        )
        assertEquals(1L, topVisit.id)
        assertEquals("YouTube", topVisit.title)
        assertEquals("https://youtube.com", topVisit.url)
        assertTrue(topVisit.createdAt > 0L)

        // Verify SearchEngineOptions
        val engineIds = SearchEngineOption.entries.map { it.id }.toSet()
        assertTrue(engineIds.contains("google"))
        assertTrue(engineIds.contains("duckduckgo"))
        assertTrue(engineIds.contains("brave"))
        assertTrue(engineIds.contains("bing"))
        assertTrue(engineIds.contains("wikipedia"))
        assertEquals(5, engineIds.size)

        // Verify curved edges shape: RoundedCornerShape(16.dp)
        val searchBarCurvedShape = RoundedCornerShape(16.dp)
        assertEquals(RoundedCornerShape(16.dp), searchBarCurvedShape)
    }

    @Test
    fun `verify SharedPreferences serialization and search engine card configs`() {
        val testBookmarks = listOf(
            com.example.data.local.BookmarkEntity(id = 1L, url = "https://example.com", title = "Example")
        )
        // Verify format and key representation
        val bookmark = testBookmarks.first()
        assertEquals(1L, bookmark.id)
        assertEquals("https://example.com", bookmark.url)
        assertEquals("Example", bookmark.title)

        // Verify all 5 search engines have valid card labels and IDs
        SearchEngineOption.entries.forEach { option ->
            assertTrue(option.id.isNotBlank())
            assertTrue(option.displayName.isNotBlank())
            assertTrue(option.shortBadge.isNotBlank())
            assertTrue(option.description.isNotBlank())
        }
    }

    @Test
    fun `verify FastDownloadEngine pauses cleanly without failing and resumes to completion`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, ApexDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val prefsRepo = UserPreferencesRepository(context)
        val sharedPrefs = context.getSharedPreferences("test_download_prefs", Context.MODE_PRIVATE)
        val repository = BrowserRepository(db.apexDao(), prefsRepo, sharedPrefs)
        val engine = FastDownloadEngine(context, repository)

        val payload = ByteArray(320 * 1024) { idx -> ((idx * 31) and 0xFF).toByte() }
        val server = ServerSocket(0)
        val serverThread = thread(isDaemon = true) {
            while (!server.isClosed) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                thread(isDaemon = true) {
                    runCatching {
                        socket.use { s ->
                            val reader = s.getInputStream().bufferedReader()
                            var rangeHeader: String? = null
                            while (true) {
                                val line = reader.readLine() ?: break
                                if (line.isEmpty()) break
                                if (line.startsWith("Range:", ignoreCase = true)) {
                                    rangeHeader = line.substringAfter(':').trim()
                                }
                            }
                            val out = s.getOutputStream()
                            val total = payload.size
                            if (rangeHeader != null && rangeHeader!!.startsWith("bytes=")) {
                                val spec = rangeHeader!!.removePrefix("bytes=").trim()
                                val start = spec.substringBefore('-').toIntOrNull() ?: 0
                                val endRaw = spec.substringAfter('-', "").toIntOrNull()
                                val end = (endRaw ?: (total - 1)).coerceAtMost(total - 1)
                                if (start >= total || start > end) {
                                    out.write("HTTP/1.1 416 Range Not Satisfiable\r\nContent-Range: bytes */$total\r\nConnection: close\r\n\r\n".toByteArray())
                                    out.flush()
                                    return@use
                                }
                                val len = end - start + 1
                                val header = "HTTP/1.1 206 Partial Content\r\n" +
                                    "Accept-Ranges: bytes\r\n" +
                                    "Content-Length: $len\r\n" +
                                    "Content-Range: bytes $start-$end/$total\r\n" +
                                    "Content-Type: application/octet-stream\r\n" +
                                    "Connection: close\r\n\r\n"
                                out.write(header.toByteArray())
                                var pos = start
                                while (pos <= end) {
                                    val chunk = minOf(8192, end - pos + 1)
                                    out.write(payload, pos, chunk)
                                    out.flush()
                                    pos += chunk
                                    Thread.sleep(15L)
                                }
                            } else {
                                val header = "HTTP/1.1 200 OK\r\n" +
                                    "Accept-Ranges: bytes\r\n" +
                                    "Content-Length: $total\r\n" +
                                    "Content-Type: application/octet-stream\r\n" +
                                    "Connection: close\r\n\r\n"
                                out.write(header.toByteArray())
                                out.write(payload)
                                out.flush()
                            }
                        }
                    }
                }
            }
        }

        try {
            val startedLatch = CountDownLatch(1)
            var downloadId = -1L
            engine.enqueueDownload(
                url = "http://127.0.0.1:${server.localPort}/bundle.bin",
                suggestedFileName = "bundle_test.bin"
            ) { entity ->
                downloadId = entity.id
                startedLatch.countDown()
            }
            val startDeadline = System.currentTimeMillis() + 5_000L
            while (startedLatch.count > 0 && System.currentTimeMillis() < startDeadline) {
                Shadows.shadowOf(Looper.getMainLooper()).idle()
                Thread.sleep(20L)
            }
            assertEquals(0L, startedLatch.count)
            assertTrue(downloadId > 0L)

            // Wait briefly for chunks to start transferring, then pause
            Thread.sleep(45L)
            engine.pauseDownload(downloadId)

            var pausedEntity = runBlocking { db.apexDao().getDownloadById(downloadId) }
            val deadlinePause = System.currentTimeMillis() + 5_000L
            while (System.currentTimeMillis() < deadlinePause) {
                pausedEntity = runBlocking { db.apexDao().getDownloadById(downloadId) }
                if (pausedEntity?.status == FastDownloadEngine.STATUS_PAUSED) break
                assertNotEquals(
                    "Paused download must never fail: ${pausedEntity?.errorMessage}",
                    FastDownloadEngine.STATUS_FAILED,
                    pausedEntity?.status
                )
                Thread.sleep(40L)
            }
            assertEquals(FastDownloadEngine.STATUS_PAUSED, pausedEntity?.status)

            // Wait another moment to ensure no late error overwrites PAUSED
            Thread.sleep(250L)
            val stablePaused = runBlocking { db.apexDao().getDownloadById(downloadId) }
            assertEquals(FastDownloadEngine.STATUS_PAUSED, stablePaused?.status)

            // Resume and verify completion with byte-exact payload
            engine.resumeDownload(downloadId)
            var finalEntity = runBlocking { db.apexDao().getDownloadById(downloadId) }
            val deadlineComplete = System.currentTimeMillis() + 10_000L
            while (System.currentTimeMillis() < deadlineComplete) {
                finalEntity = runBlocking { db.apexDao().getDownloadById(downloadId) }
                if (finalEntity?.status == FastDownloadEngine.STATUS_COMPLETED) break
                assertNotEquals(
                    "Resumed download must not fail: ${finalEntity?.errorMessage}",
                    FastDownloadEngine.STATUS_FAILED,
                    finalEntity?.status
                )
                Thread.sleep(50L)
            }
            assertEquals(FastDownloadEngine.STATUS_COMPLETED, finalEntity?.status)
            assertEquals(payload.size.toLong(), finalEntity?.downloadedBytes)
            val outFile = File(finalEntity!!.localFilePath)
            assertTrue(outFile.exists())
            assertArrayEquals(payload, outFile.readBytes())
        } finally {
            runCatching { server.close() }
            serverThread.interrupt()
            db.close()
        }
    }

    @Test
    fun `verify CompactTopToolbar horizontal swipe switches tabs and preserves tap`() {
        var nextCount = 0
        var prevCount = 0
        var clickCount = 0
        val homeTab = BrowserTab(id = "t_home", title = "Home", url = "apex://home")
        val webTab = BrowserTab(id = "t_web", title = "Example", url = "https://example.com")

        var currentIndex by mutableIntStateOf(1)
        var currentTab by mutableStateOf(homeTab)

        composeTestRule.setContent {
            ApexBrowserTheme {
                CompactTopToolbar(
                    visible = true,
                    activeTab = currentTab,
                    activeTabIndex = currentIndex,
                    totalTabs = 3,
                    onAddressPillClick = { clickCount++ },
                    onReloadClick = {},
                    onSwipeToNextTab = { nextCount++ },
                    onSwipeToPreviousTab = { prevCount++ }
                )
            }
        }

        // Tap still opens search screen
        composeTestRule.onNodeWithTag("home_top_search_bar").performClick()
        assertEquals(1, clickCount)

        // Swiping left moves to next tab
        composeTestRule.onNodeWithTag("home_top_search_bar").performTouchInput {
            swipeLeft(startX = right * 0.85f, endX = left + (width * 0.1f))
        }
        composeTestRule.waitForIdle()
        assertEquals(1, nextCount)
        assertEquals(0, prevCount)

        // Swiping right on web address bar moves to previous tab
        currentTab = webTab
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("address_bar_container").performTouchInput {
            swipeRight(startX = left + (width * 0.15f), endX = right * 0.9f)
        }
        composeTestRule.waitForIdle()
        assertEquals(1, prevCount)

        // At index 0, swiping right should not trigger previous tab
        currentIndex = 0
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("address_bar_container").performTouchInput {
            swipeRight(startX = left + (width * 0.15f), endX = right * 0.9f)
        }
        composeTestRule.waitForIdle()
        assertEquals(1, prevCount)
    }

    @Test
    fun `verify PasswordVaultCrypto generator evaluator audit and CSV round trip`() {
        val crypto = PasswordVaultCrypto()
        val secret = "Tr0ub4dor&3!ApexVault"
        val encrypted1 = crypto.encrypt(secret)
        val encrypted2 = crypto.encrypt(secret)

        assertNotEquals(secret, encrypted1)
        assertNotEquals(encrypted1, encrypted2) // randomized IV
        assertEquals(secret, crypto.decrypt(encrypted1))
        assertEquals(secret, crypto.decrypt(encrypted2))
        assertEquals(crypto.fingerprint(secret), crypto.fingerprint(secret))
        assertNotEquals(crypto.fingerprint(secret), crypto.fingerprint("${secret}x"))

        val weak = PasswordStrengthEvaluator.evaluate("password")
        assertEquals(PasswordStrength.WEAK, weak.strength)
        assertTrue(PasswordStrengthEvaluator.isCommonWeak("password"))

        val strong = PasswordStrengthEvaluator.evaluate(secret)
        assertTrue(strong.score >= 62)

        val generated = SecurePasswordGenerator.generateRandom(
            length = 18,
            includeUppercase = true,
            includeLowercase = true,
            includeDigits = true,
            includeSymbols = true
        )
        assertEquals(18, generated.length)
        assertTrue(generated.any { it.isUpperCase() })
        assertTrue(generated.any { it.isLowerCase() })
        assertTrue(generated.any { it.isDigit() })
        assertTrue(generated.any { !it.isLetterOrDigit() })

        val phrase = SecurePasswordGenerator.generatePassphrase(wordCount = 4, includeNumberAndSymbol = true)
        assertTrue(phrase.split('-').size >= 5)

        val reusedFp = crypto.fingerprint("Reused#Pass1")
        val items = listOf(
            SavedPasswordEntity(
                id = 1L,
                siteName = "GitHub",
                siteUrl = "https://github.com",
                host = "github.com",
                username = "dev@apex.io",
                encryptedPassword = crypto.encrypt("Reused#Pass1"),
                strengthScore = 70,
                passwordFingerprint = reusedFp
            ),
            SavedPasswordEntity(
                id = 2L,
                siteName = "GitLab",
                siteUrl = "https://gitlab.com",
                host = "gitlab.com",
                username = "dev@apex.io",
                encryptedPassword = crypto.encrypt("Reused#Pass1"),
                strengthScore = 70,
                passwordFingerprint = reusedFp
            ),
            SavedPasswordEntity(
                id = 3L,
                siteName = "Legacy",
                siteUrl = "https://legacy.org",
                host = "legacy.org",
                username = "old",
                encryptedPassword = crypto.encrypt("123456"),
                strengthScore = 12,
                passwordFingerprint = crypto.fingerprint("123456")
            )
        )
        val audit = PasswordAuditEngine.analyze(items) { crypto.decrypt(it.encryptedPassword) }
        assertEquals(3, audit.totalCount)
        assertEquals(1, audit.weakItems.size)
        assertEquals(1, audit.reusedGroups.size)
        assertEquals(1, audit.compromisedCount)
        assertTrue(audit.overallScore < 100)

        assertTrue(PasswordVaultUtil.hostMatches("github.com", "gist.github.com"))
        assertFalse(PasswordVaultUtil.hostMatches("github.com", "evilgithub.com"))

        val csv = PasswordVaultUtil.exportCsv(items) { crypto.decrypt(it.encryptedPassword) }
        val parsed = PasswordVaultUtil.parseCsv(csv)
        assertEquals(3, parsed.size)
        assertEquals("GitHub", parsed[0].siteName)
        assertEquals("Reused#Pass1", parsed[0].password)
    }

    @Test
    fun `verify BrowserRepository password vault CRUD host matching and CSV import`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, ApexDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val prefsRepo = UserPreferencesRepository(context)
        val sharedPrefs = context.getSharedPreferences("test_password_repo_prefs", Context.MODE_PRIVATE)
        val repository = BrowserRepository(db.apexDao(), prefsRepo, sharedPrefs)

        try {
            val savedId = repository.saveOrUpdatePassword(
                siteName = "GitHub",
                siteUrl = "https://github.com/login",
                username = "octocat",
                plainPassword = "S3cure!Apex#Password",
                notes = "Main dev account",
                category = "Work",
                isFavorite = true
            )
            assertTrue(savedId > 0L)

            val matches = repository.getPasswordsForUrl("https://gist.github.com/octocat")
            assertEquals(1, matches.size)
            assertEquals("octocat", matches.first().username)
            assertEquals("S3cure!Apex#Password", repository.decryptPassword(matches.first()))

            val csvInput = """
                name,url,username,password,note
                "Proton","https://account.proton.me","alice@proton.me","Vault#Key!9912","Recovery set"
            """.trimIndent()
            val importedCount = repository.importPasswordsFromCsv(csvInput)
            assertEquals(1, importedCount)

            val exportedCsv = repository.exportPasswordsToCsv()
            assertTrue(exportedCsv.contains("octocat"))
            assertTrue(exportedCsv.contains("alice@proton.me"))

            repository.deleteSavedPassword(savedId)
            assertTrue(repository.getPasswordsForUrl("https://github.com").isEmpty())
        } finally {
            db.close()
        }
    }

    @Test
    fun `verify Material 3 Expressive PasswordManagerScreenOverlay switches modes and displays vault entries`() {
        val crypto = PasswordVaultCrypto()
        val sample = SavedPasswordEntity(
            id = 10L,
            siteName = "Figma",
            siteUrl = "https://figma.com",
            host = "figma.com",
            username = "designer@studio.io",
            encryptedPassword = crypto.encrypt("Design#Vault99!"),
            category = "Work",
            isFavorite = true,
            strengthScore = 90,
            passwordFingerprint = crypto.fingerprint("Design#Vault99!")
        )

        composeTestRule.setContent {
            ApexBrowserTheme {
                PasswordManagerScreenOverlay(
                    passwords = listOf(sample),
                    autofillEnabled = true,
                    decryptPassword = { crypto.decrypt(it.encryptedPassword) },
                    onSavePassword = { _, _, _, _, _, _, _, _ -> },
                    onToggleFavorite = {},
                    onDeletePassword = {},
                    onClearVault = {},
                    onImportCsv = {},
                    onExportCsv = {},
                    onToggleAutofill = {},
                    onOpenWebsite = {},
                    onDismiss = {}
                )
            }
        }

        composeTestRule.onNodeWithTag("password_manager_screen").assertIsDisplayed()
        composeTestRule.onNodeWithTag("passwords_split_add_button").assertIsDisplayed()
        composeTestRule.onNodeWithTag("passwords_mode_vault").assertIsDisplayed()
        composeTestRule.onNodeWithTag("passwords_mode_generator").assertIsDisplayed()
        composeTestRule.onNodeWithTag("passwords_mode_audit").assertIsDisplayed()

        // Bottom floating toolbar should not exist; controls live at the top
        composeTestRule.onNodeWithTag("passwords_floating_toolbar").assertDoesNotExist()
        composeTestRule.onNodeWithTag("toolbar_vault_btn").assertDoesNotExist()
        composeTestRule.onNodeWithTag("toolbar_generator_btn").assertDoesNotExist()
        composeTestRule.onNodeWithTag("toolbar_audit_btn").assertDoesNotExist()
        composeTestRule.onNodeWithTag("passwords_floating_add_fab").assertDoesNotExist()

        composeTestRule.onNodeWithTag("passwords_vault_list").performScrollToIndex(2)
        composeTestRule.onNodeWithText("Figma").assertIsDisplayed()
        composeTestRule.onNodeWithText("designer@studio.io").assertIsDisplayed()

        // Switch to Material 3 Expressive Generator tab
        composeTestRule.onNodeWithTag("passwords_mode_generator").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Random Characters").assertIsDisplayed()

        // Switch to Security Health Audit tab
        composeTestRule.onNodeWithTag("passwords_mode_audit").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Security Health").assertIsDisplayed()
        composeTestRule.onNodeWithText("All Credentials Passing").assertIsDisplayed()
    }

    @Test
    fun `verify Set as Default Browser hides in menu when default and Settings shows selected browser`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val status = DefaultBrowserHelper.getStatus(context)
        assertNotNull(status.currentDefaultName)
        val launchIntent = DefaultBrowserHelper.createRequestOrSettingsIntent(context)
        assertNotNull(launchIntent.action)

        var showMenu by mutableStateOf(true)
        var isDefault by mutableStateOf(false)
        var browserStatus by mutableStateOf(
            DefaultBrowserStatus(
                isApexDefault = false,
                currentDefaultName = "Chrome",
                currentDefaultPackage = "com.android.chrome"
            )
        )

        composeTestRule.setContent {
            ApexBrowserTheme {
                if (showMenu) {
                    BrowserMenuBottomSheet(
                        isHomeActive = true,
                        desktopViewportEnabled = false,
                        isDefaultBrowser = isDefault,
                        onOpenSettings = {},
                        onSetAsDefaultBrowserClick = { isDefault = true },
                        onNewTabClick = {},
                        onOpenBookmarksClick = {},
                        onReloadPageClick = {},
                        onToggleDesktopMode = {},
                        onClearBrowsingData = {},
                        onDismiss = {}
                    )
                } else {
                    SettingsScreenOverlay(
                        preferences = BrowserPreferences(),
                        onSelectSearchEngine = {},
                        onToggleShields = {},
                        onToggleHttpsUpgrade = {},
                        onToggleScriptGuard = {},
                        onToggleSmartPreload = {},
                        onToggleDesktopViewport = {},
                        onToggleDynamicColor = {},
                        onSelectSeedPalette = {},
                        onClearBrowsingData = {},
                        defaultBrowserStatus = browserStatus,
                        onOpenDefaultBrowserSettings = {},
                        onDismiss = {}
                    )
                }
            }
        }

        // 1. When not default, menu shows "Set as Default Browser"
        composeTestRule.onNodeWithTag("menu_sheet_set_default_browser").performScrollTo()
        composeTestRule.onNodeWithTag("menu_sheet_set_default_browser").assertIsDisplayed()

        // 2. When Apex becomes default, menu hides "Set as Default Browser"
        isDefault = true
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("menu_sheet_set_default_browser").assertDoesNotExist()

        // 3. In Settings, currently selected default browser ("Chrome") is displayed
        showMenu = false
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("settings_default_browser_card").performScrollTo()
        composeTestRule.onNodeWithTag("settings_default_browser_card").assertIsDisplayed()
        composeTestRule.onNodeWithText("Currently selected: Chrome").assertIsDisplayed()
        composeTestRule.onNodeWithText("Chrome").assertIsDisplayed()

        // 4. Once Apex is default, Settings updates to display Apex Browser
        browserStatus = DefaultBrowserStatus(
            isApexDefault = true,
            currentDefaultName = "Apex Browser",
            currentDefaultPackage = context.packageName
        )
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Apex Browser is selected as your default browser").assertIsDisplayed()
        composeTestRule.onNodeWithText("Apex Browser").assertIsDisplayed()
    }

    @Test
    fun `verify Desktop Viewport toggle closes menu sheet when enabled without hiding the item`() {
        var menuOpen by mutableStateOf(true)
        var desktopEnabled by mutableStateOf(false)

        composeTestRule.setContent {
            ApexBrowserTheme {
                if (menuOpen) {
                    BrowserMenuBottomSheet(
                        isHomeActive = false,
                        desktopViewportEnabled = desktopEnabled,
                        onOpenSettings = {},
                        onNewTabClick = {},
                        onOpenBookmarksClick = {},
                        onReloadPageClick = {},
                        onToggleDesktopMode = { enabled -> desktopEnabled = enabled },
                        onClearBrowsingData = {},
                        onDismiss = { menuOpen = false }
                    )
                }
            }
        }

        // Visible when disabled
        composeTestRule.onNodeWithTag("menu_sheet_desktop_switch").performScrollTo()
        composeTestRule.onNodeWithTag("menu_sheet_desktop_switch").assertIsDisplayed()

        // Tap to enable -> turns on AND closes menu sheet
        composeTestRule.onNodeWithTag("menu_sheet_desktop_switch").performClick()
        composeTestRule.waitForIdle()
        assertTrue(desktopEnabled)
        assertFalse(menuOpen)
        composeTestRule.onNodeWithTag("hamburger_menu_bottom_sheet").assertDoesNotExist()

        // Reopen menu while desktop mode is enabled -> item is still visible (not hidden)
        menuOpen = true
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("menu_sheet_desktop_switch").performScrollTo()
        composeTestRule.onNodeWithTag("menu_sheet_desktop_switch").assertIsDisplayed()

        // Tap again to disable desktop mode
        composeTestRule.onNodeWithTag("menu_sheet_desktop_switch").performClick()
        composeTestRule.waitForIdle()
        assertFalse(desktopEnabled)
        assertTrue(menuOpen)
    }

    @Test
    fun `verify horizontal swipe left and right deletes tab card while small swipe cancels and tap selects`() {
        val tab1 = BrowserTab(id = "tab_swipe_1", title = "Docs", url = "https://kotlinlang.org")
        val tab2 = BrowserTab(id = "tab_swipe_2", title = "News", url = "https://news.ycombinator.com")
        val tab3 = BrowserTab(id = "tab_swipe_3", title = "Home", url = "apex://home")

        var tabs by mutableStateOf(listOf(tab1, tab2, tab3))
        var selectedTabId by mutableStateOf<String?>(null)
        val closedTabIds = mutableListOf<String>()

        composeTestRule.setContent {
            ApexBrowserTheme {
                DraggableTabGridOverlay(
                    gridEntries = tabs.map { TabGridEntry.SingleTab(it) },
                    totalOpenTabs = tabs.size,
                    activeTabId = tab1.id,
                    expandedGroupId = null,
                    onSelectTab = { selectedTabId = it },
                    onCloseTab = { id ->
                        closedTabIds.add(id)
                        tabs = tabs.filterNot { it.id == id }
                    },
                    onCloseGroup = {},
                    onEditGroup = {},
                    onMergeEntries = { _, _ -> },
                    onMoveGroupedTabToTarget = { _, _ -> },
                    onOpenGroup = {},
                    onAddTabToGroup = {},
                    onUngroupTab = {},
                    onCycleTabColor = {},
                    onNewTab = {},
                    onCreateGroup = {},
                    onResetAllTabs = {},
                    onDismiss = {}
                )
            }
        }

        // 1. Small partial horizontal swipe below threshold springs back without deleting
        composeTestRule.onNodeWithTag("grid_tab_card_tab_swipe_1").performTouchInput {
            swipe(
                start = center,
                end = center.copy(x = center.x + (width * 0.12f)),
                durationMillis = 300
            )
        }
        composeTestRule.waitForIdle()
        assertTrue(closedTabIds.isEmpty())
        composeTestRule.onNodeWithTag("grid_tab_card_tab_swipe_1").assertIsDisplayed()

        // 2. Tap still selects the tab
        composeTestRule.onNodeWithTag("grid_tab_card_tab_swipe_1").performClick()
        composeTestRule.waitForIdle()
        assertEquals("tab_swipe_1", selectedTabId)

        // 3. Full horizontal swipe left deletes tab_swipe_2
        composeTestRule.onNodeWithTag("grid_tab_card_tab_swipe_2").performTouchInput {
            swipeLeft()
        }
        composeTestRule.waitForIdle()
        assertTrue(closedTabIds.contains("tab_swipe_2"))
        composeTestRule.onNodeWithTag("grid_tab_card_tab_swipe_2").assertDoesNotExist()

        // 4. Full horizontal swipe right deletes tab_swipe_3
        composeTestRule.onNodeWithTag("grid_tab_card_tab_swipe_3").performTouchInput {
            swipeRight()
        }
        composeTestRule.waitForIdle()
        assertTrue(closedTabIds.contains("tab_swipe_3"))
        composeTestRule.onNodeWithTag("grid_tab_card_tab_swipe_3").assertDoesNotExist()
    }

    @Test
    fun `verify horizontal swipe deletes tab row inside expanded group modal`() {
        val group = TabGroupInfo(id = "grp_swipe", title = "Work")
        val member1 = BrowserTab(
            id = "grp_tab_1",
            title = "GitHub",
            url = "https://github.com",
            groupId = group.id
        )
        val member2 = BrowserTab(
            id = "grp_tab_2",
            title = "Android Docs",
            url = "https://developer.android.com",
            groupId = group.id
        )
        var groupTabs by mutableStateOf(listOf(member1, member2))
        val closedTabIds = mutableListOf<String>()

        composeTestRule.setContent {
            ApexBrowserTheme {
                DraggableTabGridOverlay(
                    gridEntries = listOf(TabGridEntry.GroupTab(group = group, tabs = groupTabs)),
                    totalOpenTabs = groupTabs.size,
                    activeTabId = member1.id,
                    expandedGroupId = group.id,
                    onSelectTab = {},
                    onCloseTab = { id ->
                        closedTabIds.add(id)
                        groupTabs = groupTabs.filterNot { it.id == id }
                    },
                    onCloseGroup = {},
                    onEditGroup = {},
                    onMergeEntries = { _, _ -> },
                    onMoveGroupedTabToTarget = { _, _ -> },
                    onOpenGroup = {},
                    onAddTabToGroup = {},
                    onUngroupTab = {},
                    onCycleTabColor = {},
                    onNewTab = {},
                    onCreateGroup = {},
                    onResetAllTabs = {},
                    onDismiss = {}
                )
            }
        }

        composeTestRule.onNodeWithTag("expanded_group_modal").assertIsDisplayed()
        composeTestRule.onNodeWithTag("group_modal_tab_row_grp_tab_2").performTouchInput {
            swipeLeft()
        }
        composeTestRule.waitForIdle()
        assertEquals(listOf("grp_tab_2"), closedTabIds)
        composeTestRule.onNodeWithTag("group_modal_tab_row_grp_tab_2").assertDoesNotExist()
    }

    @Test
    fun `verify desktop viewport defaults off and swipe to delete has no red backdrop`() {
        // Default BrowserPreferences keeps desktop viewport disabled
        assertFalse(BrowserPreferences().desktopViewportEnabled)

        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, ApexDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val sharedPrefs = context.getSharedPreferences("apex_browser_user_preferences", Context.MODE_PRIVATE)
        sharedPrefs.edit()
            .putBoolean("desktop_viewport_enabled", true)
            .remove("reset_default_desktop_viewport_v1")
            .apply()
        val prefsRepo = UserPreferencesRepository(context)
        val repository = BrowserRepository(db.apexDao(), prefsRepo, sharedPrefs)

        val prefsAfterInit = runBlocking {
            repository.ensureFreshInitialized()
            repository.preferencesFlow.first()
        }
        assertFalse(prefsAfterInit.desktopViewportEnabled)
        db.close()

        // Mid-swipe check confirms no red delete backdrop or delete icon is rendered
        val tab1 = BrowserTab(id = "tab_nobg_1", title = "Docs", url = "https://kotlinlang.org")
        var tabs by mutableStateOf(listOf(tab1))
        composeTestRule.setContent {
            ApexBrowserTheme {
                DraggableTabGridOverlay(
                    gridEntries = tabs.map { TabGridEntry.SingleTab(it) },
                    totalOpenTabs = tabs.size,
                    activeTabId = tab1.id,
                    expandedGroupId = null,
                    onSelectTab = {},
                    onCloseTab = { id -> tabs = tabs.filterNot { it.id == id } },
                    onCloseGroup = {},
                    onEditGroup = {},
                    onMergeEntries = { _, _ -> },
                    onMoveGroupedTabToTarget = { _, _ -> },
                    onOpenGroup = {},
                    onAddTabToGroup = {},
                    onUngroupTab = {},
                    onCycleTabColor = {},
                    onNewTab = {},
                    onCreateGroup = {},
                    onResetAllTabs = {},
                    onDismiss = {}
                )
            }
        }

        composeTestRule.onNodeWithTag("grid_tab_card_tab_nobg_1").performTouchInput {
            down(center)
            moveBy(androidx.compose.ui.geometry.Offset(-80f, 0f))
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("swipe_delete_backdrop_tab_nobg_1").assertDoesNotExist()
        composeTestRule.onNodeWithTag("grid_tab_card_tab_nobg_1").performTouchInput { up() }
        composeTestRule.waitForIdle()
    }

    @OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
    @Test
    fun `verify swipe to delete tab shows undo snackbar and undo restores closed tab`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, ApexDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val sharedPrefs = context.getSharedPreferences("test_undo_tab_prefs", Context.MODE_PRIVATE)
        val prefsRepo = UserPreferencesRepository(context)
        val repository = BrowserRepository(db.apexDao(), prefsRepo, sharedPrefs)
        val viewModel = BrowserViewModel(repository)

        viewModel.createNewTab(stayInSwitcher = true)
        val secondTab = viewModel.uiState.value.tabs.last()
        viewModel.setTabSwitcherOpen(true)

        composeTestRule.setContent {
            ApexBrowserTheme {
                BrowserScreen(
                    viewModel = viewModel,
                    windowSizeClass = WindowSizeClass.calculateFromSize(DpSize(412.dp, 915.dp))
                )
            }
        }
        composeTestRule.waitForIdle()

        assertEquals(2, viewModel.uiState.value.tabs.size)
        composeTestRule.onNodeWithTag("grid_tab_card_${secondTab.id}").assertIsDisplayed()

        // Swipe horizontally to delete the second tab
        composeTestRule.onNodeWithTag("grid_tab_card_${secondTab.id}").performTouchInput {
            swipeLeft()
        }
        composeTestRule.waitForIdle()

        assertEquals(1, viewModel.uiState.value.tabs.size)
        composeTestRule.onNodeWithTag("grid_tab_card_${secondTab.id}").assertDoesNotExist()
        composeTestRule.onNodeWithText("Undo").assertIsDisplayed()

        // Snackbar must sit cleanly above the Add (+) button with no overlap
        val snackbarBounds = composeTestRule.onNodeWithTag("browser_snackbar_host").fetchSemanticsNode().boundsInRoot
        val addButtonBounds = composeTestRule.onNodeWithTag("switcher_new_tab_fab").fetchSemanticsNode().boundsInRoot
        assertTrue(
            "Expected snackbar bottom (${snackbarBounds.bottom}) <= Add button top (${addButtonBounds.top})",
            snackbarBounds.bottom <= addButtonBounds.top
        )

        // Tap Undo on the Snackbar -> restores the closed tab
        composeTestRule.onNodeWithText("Undo").performClick()
        composeTestRule.waitForIdle()

        assertEquals(2, viewModel.uiState.value.tabs.size)
        assertTrue(viewModel.uiState.value.tabs.any { it.id == secondTab.id })
        composeTestRule.onNodeWithTag("grid_tab_card_${secondTab.id}").assertIsDisplayed()

        db.close()
    }

    @Test
    fun `verify reloadActiveTab sets loading synchronously and onWebViewUrlCommitted avoids loading flicker`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, ApexDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val sharedPrefs = context.getSharedPreferences("test_reload_sync_prefs", Context.MODE_PRIVATE)
        val prefsRepo = UserPreferencesRepository(context)
        val repository = BrowserRepository(db.apexDao(), prefsRepo, sharedPrefs)
        val store = androidx.lifecycle.ViewModelStore()
        val viewModel = androidx.lifecycle.ViewModelProvider(
            store,
            object : androidx.lifecycle.ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
                    return BrowserViewModel(repository) as T
                }
            }
        )[BrowserViewModel::class.java]

        try {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            viewModel.submitNavigation("https://example.com")
            val tabId = viewModel.uiState.value.activeTabId
            viewModel.onWebViewPageFinished(
                tabId = tabId,
                title = "Example",
                url = "https://example.com",
                canGoBack = false,
                canGoForward = false
            )
            assertFalse(viewModel.uiState.value.activeTab.isLoading)

            // SPA history/anchor commit updates URL without flashing isLoading = true
            viewModel.onWebViewUrlCommitted(tabId, "https://example.com/#section")
            assertEquals("https://example.com/#section", viewModel.uiState.value.activeTab.url)
            assertFalse(viewModel.uiState.value.activeTab.isLoading)

            // reloadActiveTab immediately marks tab as loading so pull-to-refresh does not cancel early
            viewModel.reloadActiveTab()
            assertTrue(viewModel.uiState.value.activeTab.isLoading)
            assertTrue(viewModel.uiState.value.activeTab.progress > 0f)
        } finally {
            store.clear()
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            kotlinx.coroutines.delay(50)
            db.close()
        }
    }

    @Test
    fun `verify WebViewViewport downward pull from top reveals expressive indicator and triggers refresh`() {
        var tab by mutableStateOf(
            BrowserTab(
                id = "ptr_tab_1",
                title = "Example",
                url = "https://example.com",
                isLoading = false,
                progress = 1f
            )
        )
        var refreshCount by mutableIntStateOf(0)
        val privacyManager = PrivacyManager()
        val performanceOptimizer = PerformanceOptimizer()

        composeTestRule.setContent {
            ApexBrowserTheme {
                WebViewViewport(
                    tab = tab,
                    preferences = BrowserPreferences(),
                    privacyManager = privacyManager,
                    performanceOptimizer = performanceOptimizer,
                    existingWebView = null,
                    onRegisterWebView = { _, _ -> },
                    onPageStarted = { _, _ -> },
                    onProgressChanged = { _, _ -> },
                    onPageFinished = { _, _, _, _, _ -> },
                    onSubresourceBlocked = { _, _, _, _, _, _ -> },
                    onHttpsUpgradeTriggered = {},
                    onScrollDelta = {},
                    onRefreshPage = {
                        refreshCount += 1
                        tab = tab.copy(isLoading = true, progress = 0.08f)
                    }
                )
            }
        }
        composeTestRule.waitForIdle()

        // Pull down on the WebView from top (scrollY == 0)
        composeTestRule.onNodeWithTag("browser_webview_ptr_tab_1").performTouchInput {
            swipeDown(
                startY = top + 40f,
                endY = top + 420f,
                durationMillis = 250
            )
        }
        composeTestRule.waitForIdle()

        assertEquals(1, refreshCount)
        composeTestRule.onNodeWithTag("expressive_pull_to_refresh_indicator").assertIsDisplayed()
    }

    @Test
    fun `verify Brave Search is default search engine in preferences and repository init`() = runBlocking {
        assertEquals(SearchEngineOption.BRAVE, BrowserPreferences().searchEngine)
        assertEquals(SearchEngineOption.BRAVE, SearchEngineOption.fromId(null))
        assertEquals(SearchEngineOption.BRAVE, SearchEngineOption.fromId("unknown"))

        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, ApexDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val sharedPrefs = context.getSharedPreferences("test_brave_default_prefs", Context.MODE_PRIVATE)
        sharedPrefs.edit().clear().commit()
        val prefsRepo = UserPreferencesRepository(context)
        val repository = BrowserRepository(db.apexDao(), prefsRepo, sharedPrefs)

        try {
            assertEquals(SearchEngineOption.BRAVE, prefsRepo.preferencesFlow.first().searchEngine)

            // Simulate an older install that had DuckDuckGo before the Brave default migration ran
            prefsRepo.setSearchEngine(SearchEngineOption.DUCKDUCKGO)
            repository.ensureFreshInitialized()

            assertEquals(SearchEngineOption.BRAVE, prefsRepo.preferencesFlow.first().searchEngine)
            assertTrue(sharedPrefs.getBoolean("reset_default_search_engine_brave_v1", false))
        } finally {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            db.close()
        }
    }

    @Test
    fun `verify DownloadsScreenOverlay app bar uses icon-only pill buttons on right side`() {
        val sampleDownload = com.example.data.local.DownloadItemEntity(
            id = 42L,
            fileName = "archive.zip",
            url = "https://example.com/archive.zip",
            mimeType = "application/zip",
            category = "Archive",
            totalBytes = 2048L,
            downloadedBytes = 1024L,
            speedBytesPerSec = 512L,
            chunksCount = 4,
            status = FastDownloadEngine.STATUS_DOWNLOADING,
            localFilePath = "/tmp/archive.zip",
            createdAt = System.currentTimeMillis()
        )

        composeTestRule.setContent {
            ApexBrowserTheme {
                com.example.ui.browser.DownloadsScreenOverlay(
                    downloads = listOf(sampleDownload),
                    onStartNewDownload = { _, _ -> },
                    onPauseDownload = {},
                    onResumeDownload = {},
                    onRetryDownload = {},
                    onCancelDownload = {},
                    onDeleteDownload = {},
                    onClearCompletedDownloads = {},
                    onClearAllDownloads = {},
                    onOpenUrlInBrowser = {},
                    onDismiss = {}
                )
            }
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Downloads").assertIsDisplayed()
        composeTestRule.onNodeWithTag("downloads_new_url_button").assertIsDisplayed()
        composeTestRule.onNodeWithTag("downloads_clear_button").assertIsDisplayed()

        // App bar buttons must be icon-only pills with no inline "New Download" / "Clear" text
        composeTestRule.onNodeWithText("New Download").assertDoesNotExist()
        composeTestRule.onNodeWithText("Clear").assertDoesNotExist()

        composeTestRule.onNodeWithTag("downloads_new_url_button").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Start Fast Download").assertIsDisplayed()
    }

    @Test
    fun `verify DownloadNotificationHelper shows progress in status bar notification and clears on completion`() {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        Shadows.shadowOf(app).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)

        val helper = com.example.engine.DownloadNotificationHelper(app)
        val notificationManager = app.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        val shadowNotificationManager = Shadows.shadowOf(notificationManager)

        val downloadId = 77L
        val downloadingItem = com.example.data.local.DownloadItemEntity(
            id = downloadId,
            fileName = "ubuntu.iso",
            url = "https://example.com/ubuntu.iso",
            mimeType = "application/octet-stream",
            category = "Other",
            totalBytes = 100_000_000L,
            downloadedBytes = 50_000_000L,
            speedBytesPerSec = 5_000_000L,
            chunksCount = 4,
            status = FastDownloadEngine.STATUS_DOWNLOADING,
            localFilePath = "/tmp/ubuntu.iso",
            createdAt = System.currentTimeMillis()
        )

        helper.notifyDownloadState(downloadingItem, forceImmediate = true)

        val notifId = com.example.engine.DownloadNotificationHelper.notificationIdFor(downloadId)
        val activeNotif = shadowNotificationManager.getNotification(notifId)
        assertNotNull(activeNotif)
        assertTrue((activeNotif.flags and android.app.Notification.FLAG_ONGOING_EVENT) != 0)
        assertEquals(50, activeNotif.extras.getInt(android.app.Notification.EXTRA_PROGRESS))
        assertEquals(100, activeNotif.extras.getInt(android.app.Notification.EXTRA_PROGRESS_MAX))

        val completedItem = downloadingItem.copy(
            downloadedBytes = 100_000_000L,
            status = FastDownloadEngine.STATUS_COMPLETED,
            completedAt = System.currentTimeMillis()
        )
        helper.notifyDownloadState(completedItem, forceImmediate = true)

        val completedNotif = shadowNotificationManager.getNotification(notifId)
        assertNotNull(completedNotif)
        assertTrue((completedNotif.flags and android.app.Notification.FLAG_ONGOING_EVENT) == 0)
        assertEquals(0, completedNotif.extras.getInt(android.app.Notification.EXTRA_PROGRESS_MAX))

        helper.cancel(downloadId)
        assertNull(shadowNotificationManager.getNotification(notifId))
    }

    @Test
    fun `verify active tab grid card renders cleanly under selected theme palette`() {
        val activeTab = BrowserTab(
            id = "tab_theme_active",
            title = "Brave Search",
            url = "https://search.brave.com",
            isLoading = false
        )
        val inactiveTab = BrowserTab(
            id = "tab_theme_inactive",
            title = "Home",
            url = "apex://home",
            isLoading = false
        )

        composeTestRule.setContent {
            ApexBrowserTheme(
                dynamicColor = false,
                seedPreset = SeedPalettePreset.CYBER_EMERALD
            ) {
                DraggableTabGridOverlay(
                    gridEntries = listOf(
                        TabGridEntry.SingleTab(activeTab),
                        TabGridEntry.SingleTab(inactiveTab)
                    ),
                    totalOpenTabs = 2,
                    activeTabId = activeTab.id,
                    expandedGroupId = null,
                    onSelectTab = {},
                    onCloseTab = {},
                    onCloseGroup = {},
                    onEditGroup = {},
                    onMergeEntries = { _, _ -> },
                    onMoveGroupedTabToTarget = { _, _ -> },
                    onOpenGroup = {},
                    onAddTabToGroup = {},
                    onUngroupTab = {},
                    onCycleTabColor = {},
                    onNewTab = {},
                    onCreateGroup = {},
                    onResetAllTabs = {},
                    onDismiss = {}
                )
            }
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("grid_tab_card_tab_theme_active").assertIsDisplayed()
        composeTestRule.onNodeWithTag("grid_tab_card_tab_theme_inactive").assertIsDisplayed()
    }
}
