package tw.language.listener

import android.app.Application
import android.content.Context
import android.content.res.Configuration
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import tw.language.listener.data.*
import tw.language.listener.i18n.*
import kotlinx.coroutines.flow.first
import java.util.Locale
import kotlinx.serialization.json.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],application=Application::class)
class LocalizationTest {
    private fun context(tag: String): Context {
        val base=ApplicationProvider.getApplicationContext<Application>()
        return base.createConfigurationContext(Configuration(base.resources.configuration).apply { setLocale(Locale.forLanguageTag(tag)) })
    }
    @Test fun everyMessageHasEnglishAndIndonesianTranslationWithValidArguments() {
        for(tag in listOf("en","id")) {
            val ui=UiStrings(context(tag))
            MessageCatalog.entries.forEach { (canonical,id) ->
                val placeholders=Regex("%([0-9]+)\\\$s").findAll(canonical).map { it.groupValues[1].toInt() }.maxOrNull() ?: 0
                val text=ui(id,*Array(placeholders) { "value${it+1}" })
                assertFalse("$tag missing $canonical",Regex("[\\u3400-\\u9fff]").containsMatchIn(text))
                assertFalse(text.contains("%1\$s"))
            }
        }
        assertEquals("首頁",UiStrings(context("zh-TW"))(R.string.home_fdf1f0))
        assertEquals("Home",UiStrings(context("en"))(R.string.home_fdf1f0))
        assertEquals("Beranda",UiStrings(context("id"))(R.string.home_fdf1f0))
    }
    @Test fun persistedDiagnosticsTranslateAndDynamicLessonContentRemainsIntact() {
        val ui=UiStrings(context("en"))
        assertEquals("Home",ui.message("首頁"))
        assertEquals("Unknown service diagnostic",ui.message("Unknown service diagnostic"))
        val translated=ui.message("備份校驗失敗：中文教材.jsonl")
        assertTrue(translated.contains("中文教材.jsonl"))
        assertFalse(translated.contains("校驗失敗"))
        assertTrue(ui.message("Google Sheets：HTTP 403，請檢查授權、API 設定或配額").contains("403"))
        assertEquals("1.25",ui.decimal(1.25f))
        assertEquals("1,25",UiStrings(context("id")).decimal(1.25f))
    }
    @Test fun changingLanguagePreservesStableProgressSessionAndQueue() = runBlocking {
        val db=Room.inMemoryDatabaseBuilder(context("zh-TW"),ListenerDatabase::class.java).allowMainThreadQueries().build()
        try {
            val repo=Repository(db); repo.seed()
            repo.createSession(PlaybackOptions(excludeLearned=false))
            val queue=repo.dao.queue(); assertTrue(queue.isNotEmpty())
            val item=queue.first()
            repo.updateProgress(item.sourceId,item.itemId) { it.copy(favorite=true,playCount=4) }
            val progress=repo.dao.progress(item.sourceId,item.itemId)
            val session=repo.dao.session()
            for(language in listOf("en","id","zh-TW")) {
                repo.saveSettings(repo.dao.settings()!!.copy(uiLanguage=language))
                assertEquals(language,repo.dao.settings()!!.uiLanguage)
                assertEquals(queue,repo.dao.queue()); assertEquals(session,repo.dao.session())
                assertEquals(progress,repo.dao.progress(item.sourceId,item.itemId))
            }
        } finally { db.close() }
    }
    @Test fun v2MigrationKeepsSettingsDraftAndQueue() = runBlocking {
        val base=context("zh-TW")
        val name="locale-migration-${System.nanoTime()}.db"
        val file=base.getDatabasePath(name).apply { parentFile!!.mkdirs() }
        val schema=kotlinx.serialization.json.Json.parseToJsonElement(checkNotNull(javaClass.classLoader!!.getResourceAsStream("tw.language.listener.data.ListenerDatabase/2.json")).bufferedReader().use { it.readText() }).jsonObject["database"]!!.jsonObject
        android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(file,null).use { old ->
            schema["entities"]!!.jsonArray.forEach { entity ->
                val table=entity.jsonObject
                old.execSQL(table["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}",table["tableName"]!!.jsonPrimitive.content))
                table["indices"]!!.jsonArray.forEach { index -> old.execSQL(index.jsonObject["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}",table["tableName"]!!.jsonPrimitive.content)) }
            }
            schema["setupQueries"]!!.jsonArray.forEach { old.execSQL(it.jsonPrimitive.content) }
            old.execSQL("INSERT INTO settings(singleton,playback,wifiOnly,syncOnLaunch,dark,modifiedAt,aiProfile) VALUES (1,?,1,0,1,123,?)",arrayOf(Converters().encode(PlaybackOptions(count=50)),Converters().encodeProfile(tw.language.listener.authoring.AiProfile("openai","https://api.openai.com/v1","gpt-4o-mini"))))
            old.execSQL("INSERT INTO queue VALUES (1,'stable-source','item-1')")
            old.execSQL("INSERT INTO ai_drafts VALUES (1,'durable-draft')")
            old.version=2
        }
        val db=Room.databaseBuilder(base,ListenerDatabase::class.java,name).addMigrations(Migrations.V2_TO_V3,Migrations.V3_TO_V4,Migrations.V4_TO_V5,Migrations.V5_TO_V6).allowMainThreadQueries().build()
        try {
            val settings=db.dao().settings()!!
            assertEquals("zh-TW",settings.uiLanguage); assertTrue(settings.dark); assertTrue(settings.wifiOnly)
            assertFalse(settings.syncOnLaunch); assertEquals(50,settings.playback.count)
            assertEquals("gpt-4o-mini",settings.aiProfile!!.model)
            assertEquals("durable-draft",db.dao().observeDraft().first()!!.payload)
            assertEquals("stable-source",db.dao().queue().single().sourceId)
        } finally { db.close(); base.deleteDatabase(name) }
    }

}
