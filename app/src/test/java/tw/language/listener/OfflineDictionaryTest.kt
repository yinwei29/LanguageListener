package tw.language.listener

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import tw.language.listener.authoring.*
import tw.language.listener.data.*
import tw.language.listener.dictionary.*
import tw.language.listener.documents.*
import tw.language.listener.review.Rating
import java.io.File
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],application=Application::class)
class OfflineDictionaryTest {
    private lateinit var repo: Repository
    private lateinit var dir: File
    private lateinit var dictionaries: OfflineDictionary
    @Before fun setup()=runBlocking {
        val app=ApplicationProvider.getApplicationContext<Application>()
        repo=Repository(Room.inMemoryDatabaseBuilder(app,ListenerDatabase::class.java).allowMainThreadQueries().build());repo.seed()
        dir=File(app.cacheDir,"dictionary-${UUID.randomUUID()}");dictionaries=OfflineDictionary(repo,dir)
    }
    @After fun close() { repo.db.close();dir.deleteRecursively() }
    private fun draft(): AiDraft {
        val pending=AiSourceDrafts.pending("旅行字典",AiRequest("ignored","Travel",count=2,languages=listOf("zh-TW","ja-JP","ko-KR")),AiProfile())
        val parsed=AuthoringValidation.parse("""{"items":[{"zh-TW":"你好","ja-JP":"こんにちは","ko-KR":"안녕하세요"},{"zh-TW":"謝謝","ja-JP":"ありがとうございます","ko-KR":"감사합니다"}]}""",pending.request)
        return pending.copy(items=parsed.items,generationComplete=true,generatedCount=2)
    }
    @Test fun reviewedAiCreatesRealCsvWithOnlySelectedStableIdsAndNoCredentials()=runBlocking {
        val value=draft();AiSourceDrafts(repo).saveCheckpoint(value)
        assertNull(dictionaries.existing(value.request.sourceId));assertFalse(dir.exists())
        val selected=listOf(value.items.first().copy(pronunciationNote="line one,\n\"note\""))
        val file=dictionaries.publishReviewed(value,selected)
        assertTrue(file.file.isFile);assertTrue(file.bytes>0);assertEquals(1,file.entries)
        val text=file.file.readText();assertTrue(text.startsWith("\uFEFF"));assertEquals(GoogleSheets.ALL_HEADERS,tw.language.listener.sync.CsvReader(text.removePrefix("\uFEFF").reader()).row())
        assertFalse(text.contains("api_key"));assertFalse(text.contains("test-key"));assertFalse(text.contains(value.items.last().itemId))
        assertEquals(selected,DocumentCodec.decode(file.file.readBytes()).items(value.request.sourceId))
        assertEquals(file,dictionaries.existing(value.request.sourceId))
    }
    @Test fun csvCanRepopulateOfflineLessonsAndBuildJapaneseKoreanQueueWithoutApi()=runBlocking {
        val value=draft();AiSourceDrafts(repo).saveCheckpoint(value);val file=dictionaries.publishReviewed(value,value.items)
        repo.rate(value.request.sourceId,value.items.first().itemId,Rating.GOOD,UUID.randomUUID().toString());val progress=repo.dao.progress(value.request.sourceId,value.items.first().itemId)
        val bytes=file.file.readBytes();repo.dao.clearItems()
        val table=DocumentCodec.decode(bytes);assertEquals(0,table.initialize())
        repo.dao.putItems(table.items(value.request.sourceId))
        assertEquals(2,repo.createSession(PlaybackOptions(sourceId=value.request.sourceId,native="ja-JP",target="ko-KR")))
        assertEquals(value.request.sourceId,repo.dao.queue().first().sourceId)
        assertEquals("こんにちは",repo.dao.item(value.request.sourceId,value.items.first().itemId)!!.text("ja-JP"))
        assertEquals(progress,repo.dao.progress(value.request.sourceId,value.items.first().itemId))
    }
    @Test fun dictionaryStorageFailureKeepsReviewedDatabaseAndCanRetry()=runBlocking {
        dir.writeText("not a directory")
        val value=draft();AiSourceDrafts(repo).saveCheckpoint(value)
        try { dictionaries.publishReviewed(value,value.items);fail("accepted invalid directory") } catch(e: IllegalStateException) { assertTrue(e.message!!.contains("教材已儲存")) }
        assertEquals(2,repo.dao.sourcePage(value.request.sourceId,null,100).size)
        assertTrue(dir.delete());val result=dictionaries.publishReviewed(value,value.items)
        assertEquals(2,result.entries)
    }
    @Test fun rejectedUnreviewedDraftCannotCreateDictionaryOrSource()=runBlocking {
        val value=draft();AiSourceDrafts(repo).saveCheckpoint(value)
        try { dictionaries.publishReviewed(value,emptyList());fail("unreviewed content published") } catch(_: IllegalArgumentException) { }
        assertFalse(dir.exists());assertFalse(repo.dao.sources().any { it.sourceId==value.request.sourceId })
    }
    @Test fun refreshAndRepeatedPublishPreserveLaterEditsAndLearningState()=runBlocking {
        val value=draft();AiSourceDrafts(repo).saveCheckpoint(value);val first=dictionaries.publishReviewed(value,value.items)
        val updated=value.items.first().copy(ja="編集済み",ko="수정됨")
        repo.saveLocalItem(updated);repo.updateProgress(updated.sourceId,updated.itemId) { it.copy(favorite=true,playCount=8) }
        val second=dictionaries.publishReviewed(value,value.items)
        assertNotEquals(first.sha256,second.sha256)
        assertEquals(updated,DocumentCodec.decode(second.file.readBytes()).items(updated.sourceId).find { it.itemId==updated.itemId })
        assertTrue(repo.dao.progress(updated.sourceId,updated.itemId)!!.favorite)
    }
    @Test fun streamingPagesKeepEveryEntryAndId()=runBlocking {
        val source=Source("paged","Paged","",access="local");repo.dao.putSources(listOf(source))
        val items=(1..513).map { Item(source.sourceId,"item-"+it.toString().padStart(4,'0'),zh="你好 $it",en="Hello $it",id="",ja="こんにちは $it",ko="안녕하세요 $it") }
        repo.dao.putItems(items);val file=dictionaries.refresh(source.sourceId)
        assertEquals(513,file.entries);assertEquals(items,DocumentCodec.decode(file.file.readBytes()).items(source.sourceId))
    }
    @Test fun sourceKeysCannotEscapeDirectoryAndRemovedSourceCannotExportOldSnapshot()=runBlocking {
        val source=Source("../../outside","Source","",access="local");repo.dao.putSources(listOf(source));repo.saveLocalItem(Item(source.sourceId,"one",zh="你好",en="Hi",id=""))
        val file=dictionaries.refresh(source.sourceId);assertEquals(dir.canonicalFile,file.file.parentFile!!.canonicalFile)
        repo.removeSource(source.sourceId);assertNull(dictionaries.existing(source.sourceId))
        try { dictionaries.refresh(source.sourceId);fail("exported removed source") } catch(_: IllegalStateException) { }
    }
    @Test fun sourceBrowserSeparatesIdenticalIdsAndKeepsRealCounters()=runBlocking {
        val a=Source("source-a","A","",access="local");val b=Source("source-b","B","",access="local")
        repo.dao.putSources(listOf(a,b))
        repo.dao.putItems(listOf(Item(a.sourceId,"same",zh="你好",en="Hello",id="",ja="こんにちは"),Item(b.sourceId,"same",zh="你好",en="Hello",id="",ko="안녕하세요")))
        repo.updateProgress(a.sourceId,"same") { it.copy(learned=true) }
        assertEquals(a.sourceId,repo.dao.searchSource(a.sourceId,"Hello","",100,0).single().sourceId)
        assertEquals(b.sourceId,repo.dao.searchSource(b.sourceId,"안녕하세요","",100,0).single().sourceId)
        val stats=repo.dao.sourceSummaries(System.currentTimeMillis()).first().associateBy { it.sourceId }
        assertEquals(1,stats[a.sourceId]!!.total);assertEquals(1,stats[a.sourceId]!!.learned);assertEquals(0,stats[b.sourceId]!!.learned)
    }
    @Test fun failedReplacementRetainsPreviousCompleteDictionary()=runBlocking {
        val value=draft();AiSourceDrafts(repo).saveCheckpoint(value);val previous=dictionaries.publishReviewed(value,value.items)
        val bytes=previous.file.readBytes()
        repo.saveLocalItem(value.items.first().copy(ja="新しい翻訳"))
        try { OfflineDictionary(repo,dir,128).refresh(value.request.sourceId);fail("accepted oversized replacement") } catch(_: IllegalArgumentException) { }
        assertArrayEquals(bytes,previous.file.readBytes())
        assertEquals("新しい翻訳",repo.dao.item(value.request.sourceId,value.items.first().itemId)!!.ja)
        assertNotEquals(previous.sha256,dictionaries.refresh(value.request.sourceId).sha256)
    }
    @Test fun clearRemovesDerivedSnapshotsAndNextRefreshUsesCurrentDatabase()=runBlocking {
        val value=draft();AiSourceDrafts(repo).saveCheckpoint(value);dictionaries.publishReviewed(value,value.items)
        dictionaries.clear();assertNull(dictionaries.existing(value.request.sourceId))
        assertEquals(2,dictionaries.refresh(value.request.sourceId).entries)
    }
}
