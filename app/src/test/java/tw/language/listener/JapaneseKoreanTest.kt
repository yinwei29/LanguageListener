package tw.language.listener

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import tw.language.listener.data.*
import tw.language.listener.authoring.*
import tw.language.listener.documents.*
import tw.language.listener.sync.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],application=Application::class)
class JapaneseKoreanTest {
    @Test fun csvAndDocumentInitializationPreserveJapaneseKoreanAndStableIds() {
        val columns=SheetColumns(listOf("ID","ja-JP","ko-KR"))
        val item=columns.item("s",listOf("stable","こんにちは","안녕하세요"))
        assertEquals("こんにちは",item.text("ja-JP"));assertEquals("안녕하세요",item.text("ko-KR"))
        val table=DocumentCodec.decode("ja-JP,ko-KR\nこんにちは,안녕하세요\n".toByteArray());assertEquals(1,table.initialize())
        val before=table.items("s").single();table.update(listOf(before))
        val after=DocumentCodec.decode(table.encode()).items("s").single()
        assertEquals(before.itemId,after.itemId);assertEquals(before.ja,after.ja);assertEquals(before.ko,after.ko)
    }
    @Test fun optionalColumnsAreAddedOnlyWhenNeededWithoutDroppingLegacyFields() {
        val table=DocumentCodec.decode(byteArrayOf());table.initialize();assertEquals(13,table.headers.size)
        val item=Item("s","one",zh="你好",en="Hello",id="Halo",ja="こんにちは",ko="안녕하세요")
        table.update(listOf(item));assertEquals(15,table.headers.size)
        assertEquals(item,DocumentCodec.decode(table.encode()).items("s").single())
        val profile=AiProfile("openai","https://api.openai.com/v1","gpt-4.1-mini",outputMode="schema")
        val schema=AiGenerator().body(profile,AiRequest("s","Travel",languages=listOf("zh-TW","ja-JP"))).toString()
        assertTrue(schema.contains("ja-JP"));assertTrue(schema.contains("ko-KR"))
    }
    @Test fun sourceAndLanguageFiltersDoNotMixGeneratedSourcesWithDemo()=runBlocking {
        val app=ApplicationProvider.getApplicationContext<Application>();val db=Room.inMemoryDatabaseBuilder(app,ListenerDatabase::class.java).allowMainThreadQueries().build();val repo=Repository(db);repo.seed()
        try {
            repo.dao.putSources(listOf(Source("japanese","Japanese","",access="local"),Source("korean","Korean","",access="local")))
            repo.saveLocalItem(Item("japanese","same",zh="你好",en="",id="",ja="こんにちは"))
            repo.saveLocalItem(Item("korean","same",zh="你好",en="",id="",ko="안녕하세요"))
            assertEquals(1,repo.createSession(PlaybackOptions(sourceId="japanese",native="zh-TW",target="ja-JP")))
            assertEquals("japanese",repo.dao.queue().single().sourceId)
            assertEquals(1,repo.createSession(PlaybackOptions(sourceId="korean",native="zh-TW",target="ko-KR")))
            assertEquals("korean",repo.dao.queue().single().sourceId)
            assertEquals(1,repo.dao.search("こんにちは","",100,0).size);assertEquals(1,repo.dao.search("안녕하세요","",100,0).size)
        } finally { db.close() }
    }
    @Test fun newLanguageRatesAreIndependentAndLegacyAiRequestsStayThreeLanguage() {
        val options=PlaybackOptions(native="ja-JP",target="ko-KR",idRate=.5f,jaRate=.7f,koRate=.9f)
        options.validate();assertEquals(.7f,options.rate("ja-JP"));assertEquals(.9f,options.rate("ko-KR"));assertEquals(.5f,options.rate("id-ID"))
        assertEquals(listOf("ja-JP","ko-KR"),options.languages())
        assertEquals(3,AiRequest("s","Travel").languages.size)
        assertThrows(IllegalArgumentException::class.java) { options.copy(jaRate=Float.NaN).validate() }
        val parsed=AuthoringValidation.parse("""{"items":[{"ja-JP":"こんにちは","ko-KR":"안녕하세요"}]}""",AiRequest("s","Travel",count=1,languages=listOf("ja-JP","ko-KR")))
        assertEquals("こんにちは",parsed.items.single().ja)
        assertNotEquals(AuthoringValidation.fingerprint(parsed.items.single()),AuthoringValidation.fingerprint(parsed.items.single().copy(ko="감사합니다")))
    }
    @Test fun cloudCellMappingDoesNotSubstituteIndonesianForJapanese() {
        val item=Item("s","i",zh="你好",en="Hello",id="Halo",ja="こんにちは",ko="안녕하세요")
        assertEquals("こんにちは",GoogleSheets.cell(item,"ja-JP"));assertEquals("안녕하세요",GoogleSheets.cell(item,"ko-KR"));assertEquals("Halo",GoogleSheets.cell(item,"id-ID"))
    }
}
