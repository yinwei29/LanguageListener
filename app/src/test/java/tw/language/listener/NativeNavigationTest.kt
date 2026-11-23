package tw.language.listener

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import androidx.media3.common.util.UnstableApi
import tw.language.listener.authoring.*
import tw.language.listener.data.*

/** Real Compose navigation and reviewed dictionary UI; HTTP/account/device audio are separate. */
@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],application=NativeNavigationTest.UiTestApplication::class,qualifiers="w412dp-h892dp")
class NativeNavigationTest {
    class UiTestApplication: ListenerApp() {
        override fun onCreate() {
            super.onCreate()
            androidx.work.WorkManager.initialize(this,androidx.work.Configuration.Builder().build())
            // JVM has no Android media-service binder; exercise the UI with that platform edge unavailable.
            org.robolectric.Shadows.shadowOf(this).declareComponentUnbindable(android.content.ComponentName(this,tw.language.listener.playback.ListeningService::class.java))
        }
    }
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    @Test fun libraryAiSettingsAndReviewedDictionaryAreReachableWithoutGoogle() {
        compose.waitUntil(15000) { compose.onAllNodesWithText("Language Listener").fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasText("教材庫") and hasClickAction()).performClick()
        compose.waitUntil(15000) { compose.onAllNodesWithText("新增來源或教材").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("新增來源或教材").performClick()
        compose.onNodeWithText("用 AI 建立字典").performClick()
        compose.onNodeWithText("設定 API 金鑰與模型").performScrollTo().performClick()
        compose.onAllNodesWithText("AI API 與模型").onFirst().assertIsDisplayed()
        compose.onNodeWithText("供應商").assertExists()
        // No key, token or Google consent is needed to publish a fixture-reviewed local draft.
        val app=ApplicationProvider.getApplicationContext<ListenerApp>()
        val initial=AiSourceDrafts.pending("離線字典測試",AiRequest("ignored","Travel",count=1,languages=listOf("zh-TW","ja-JP")),AiProfile())
        val draft=initial.copy(items=AuthoringValidation.parse("""{"items":[{"zh-TW":"你好","ja-JP":"こんにちは"}]}""",initial.request).items,generationComplete=true,generatedCount=1)
        runBlocking { AiSourceDrafts(app.repository).saveCheckpoint(draft);app.dictionaries.publishReviewed(draft,draft.items) }
        compose.onNode(hasText("教材庫") and hasClickAction()).performClick()
        compose.waitUntil(15000) { compose.onAllNodesWithText("離線字典測試").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("離線字典測試").performScrollTo().performClick()
        compose.onNodeWithText("CSV 字典檔").performScrollTo().performClick()
        compose.waitUntil(15000) { compose.onAllNodesWithText("字典檔已保存在 App").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("儲存字典檔到手機").performScrollTo().assertIsDisplayed()
        compose.activityRule.scenario.recreate()
        compose.waitUntil(15000) { compose.onAllNodesWithText("儲存字典檔到手機").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("儲存字典檔到手機").performScrollTo().assertIsDisplayed()
        assertNotNull(runBlocking { app.dictionaries.existing(initial.request.sourceId) })
        val listener=ViewModelProvider(compose.activity)[ListenerViewModel::class.java]
        runBlocking { assertEquals(1,listener.repo.createSession(PlaybackOptions(sourceId=initial.request.sourceId,native="zh-TW",target="ja-JP"))) }
    }
}
