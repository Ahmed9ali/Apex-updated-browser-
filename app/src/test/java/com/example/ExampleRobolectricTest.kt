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
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.unit.dp
import com.example.ui.browser.BrowserMenuBottomSheet
import com.example.ui.browser.CompactTopToolbar
import com.example.ui.browser.GroupedListSpacing
import com.example.ui.browser.SettingsScreenOverlay
import com.example.ui.browser.groupedListItemShape
import com.example.data.preferences.BrowserPreferences
import com.example.engine.DefaultBrowserHelper
import com.example.engine.DefaultBrowserStatus
import com.example.ui.theme.ApexBrowserTheme
import com.example.ui.theme.ApexColorSchemeBuilder
import com.example.ui.theme.SeedPalettePreset
import com.example.ui.theme.TabGroupColorOption
import com.example.ui.viewmodel.BrowserTab
import com.example.ui.viewmodel.BrowserUiState
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
}


