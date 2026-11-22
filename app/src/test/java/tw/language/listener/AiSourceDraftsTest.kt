package tw.language.listener

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import tw.language.listener.authoring.*
import tw.language.listener.data.*
import tw.language.listener.backup.*
import java.io.ByteArrayOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],application=Application::class)
class AiSourceDraftsTest {
    private lateinit var repo: Repository
    @Before fun setup()=runBlocking { repo=Repository(Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Application>(),ListenerDatabase::class.java).allowMainThreadQueries().build());repo.seed() }
    @After fun close() { repo.db.close() }
    private fun prepared(name: String="Japanese"): AiDraft {
        val initial=AiSourceDrafts.pending(name,AiRequest("ignored","Travel",count=2,languages=listOf("zh-TW","ja-JP")),AiProfile())
        val batch=AuthoringValidation.parse("""{"items":[{"zh-TW":"你好","ja-JP":"こんにちは"},{"zh-TW":"謝謝","ja-JP":"ありがとうございます"}]}""",initial.request)
        return initial.copy(items=batch.items,generatedCount=2,generationComplete=true)
    }
    @Test fun responseBecomesDraftBeforeReviewedAtomicSourceCreation()=runBlocking {
        AuthoringFixture().use { api ->
            val profile=AiProfile("compatible",api.server.url("/v1").toString(),"model",protocol="responses")
            val initial=AiSourceDrafts.pending("日文旅行",AiRequest("unused","Travel",count=2,languages=listOf("zh-TW","ja-JP")),profile)
            api.json(buildJsonObject { put("status","completed");putJsonArray("output") { add(buildJsonObject { put("type","message");putJsonArray("content") { add(buildJsonObject { put("type","output_text");put("text","""{"items":[{"ID":"untrusted","source_url":"https://untrusted.example","zh-TW":"你好","ja-JP":"こんにちは"},{"zh-TW":"謝謝","ja-JP":"ありがとうございます"}]}""") }) } }) } }.toString())
            val service=AiSourceDrafts(repo)
            val draft=AiBatchGenerator(AiGenerator(api.http)).generate(profile,"test-key",initial.request,initial,checkpoint={ service.saveCheckpoint(it) })
            assertFalse(repo.dao.sources().any { it.sourceId==initial.request.sourceId })
            assertTrue(repo.dao.sourcePage(initial.request.sourceId,null,100).isEmpty())
            val selected=listOf(draft.items.first());val published=service.publish(draft,selected)
            val source=repo.dao.sources().single { it.sourceId==draft.request.sourceId }
            assertEquals("local",source.access);assertEquals("日文旅行",source.name);assertTrue(source.url.isEmpty());assertTrue(published.written)
            val item=repo.dao.sourcePage(source.sourceId,null,100).single();assertEquals("こんにちは",item.ja);assertTrue(item.sourceUrl.isEmpty());assertNotEquals("untrusted",item.itemId)
            assertEquals(1,repo.createSession(PlaybackOptions(sourceId=source.sourceId,native="zh-TW",target="ja-JP")))
            assertEquals(source.sourceId,repo.dao.queue().single().sourceId)
            assertEquals("/v1/responses",api.server.takeRequest().path)
        }
    }
    @Test fun repeatConfirmationDoesNotOverwriteLaterEditsOrProgress()=runBlocking {
        val service=AiSourceDrafts(repo);val value=prepared();service.saveCheckpoint(value);val selected=listOf(value.items.first());service.publish(value,selected)
        repo.saveLocalItem(selected.single().copy(ja="編集済み"));repo.updateProgress(value.request.sourceId,selected.single().itemId) { it.copy(favorite=true,playCount=5) }
        service.publish(value,selected)
        assertEquals(1,repo.dao.sourcePage(value.request.sourceId,null,100).size)
        assertEquals("編集済み",repo.dao.item(value.request.sourceId,selected.single().itemId)!!.ja)
        assertTrue(repo.dao.progress(value.request.sourceId,selected.single().itemId)!!.favorite)
    }
    @Test fun staleDraftOrRestoreEpochCannotCreateSource()=runBlocking {
        val service=AiSourceDrafts(repo);val first=prepared();service.saveCheckpoint(first);val second=prepared("Korean");service.saveCheckpoint(second)
        try { service.publish(first,listOf(first.items.first()));fail("published stale draft") } catch(_: IllegalStateException) { }
        assertFalse(repo.dao.sources().any { it.sourceId==first.request.sourceId })
        val epoch=repo.syncEpoch.get();repo.syncEpoch.incrementAndGet()
        try { service.saveCheckpoint(first,epoch);fail("ignored restored state") } catch(_: IllegalStateException) { }
        assertEquals(JsonCodec.json.encodeToString(second),repo.dao.observeDraftSnapshot()!!.payload)
    }
    @Test fun failedGenerationLeavesNoEmptySourceAndKeepsPreviousDraft()=runBlocking {
        val service=AiSourceDrafts(repo);val previous=prepared();service.saveCheckpoint(previous)
        AuthoringFixture().use { api ->
            val profile=AiProfile("compatible",api.server.url("/v1").toString(),"model")
            val initial=AiSourceDrafts.pending("Failed",AiRequest("unused","Travel",count=1),profile);api.json("{}",400)
            try { AiBatchGenerator(AiGenerator(api.http)).generate(profile,"test-key",initial.request,initial,checkpoint={ service.saveCheckpoint(it) });fail("accepted failed generation") } catch(_: RemoteError) { }
            assertFalse(repo.dao.sources().any { it.sourceId==initial.request.sourceId })
            assertEquals(JsonCodec.json.encodeToString(previous),repo.dao.observeDraftSnapshot()!!.payload)
        }
    }
    @Test fun sourceRenameAndBackupPreserveIdsJapaneseAndReviewState()=runBlocking {
        val service=AiSourceDrafts(repo);val value=prepared();service.saveCheckpoint(value);service.publish(value,value.items)
        val sourceId=value.request.sourceId;val item=value.items.first()
        repo.rate(sourceId,item.itemId,tw.language.listener.review.Rating.GOOD,java.util.UUID.randomUUID().toString())
        val progress=repo.dao.progress(sourceId,item.itemId)
        repo.renameSource(sourceId,"日本語旅行")
        assertEquals(progress,repo.dao.progress(sourceId,item.itemId))
        val app=ApplicationProvider.getApplicationContext<Application>();val backup=BackupManager(repo,app.cacheDir);val out=ByteArrayOutputStream();backup.export(true,out)
        backup.preview(out.toByteArray().inputStream()).use { backup.restore(it,true) }
        assertEquals("日本語旅行",repo.dao.sources().single { it.sourceId==sourceId }.name)
        assertEquals("こんにちは",repo.dao.item(sourceId,item.itemId)!!.ja)
        assertEquals(progress,repo.dao.progress(sourceId,item.itemId))
    }
    @Test fun pendingDraftSurvivesSerializationButIsExcludedFromBackup()=runBlocking {
        val service=AiSourceDrafts(repo);val value=prepared();service.saveCheckpoint(value)
        val restored=JsonCodec.json.decodeFromString<AiDraft>(repo.dao.observeDraftSnapshot()!!.payload)
        assertEquals(value,restored);AiSourceDrafts.validate(restored)
        val out=ByteArrayOutputStream();BackupManager(repo,ApplicationProvider.getApplicationContext<Application>().cacheDir).export(true,out)
        java.util.zip.ZipInputStream(out.toByteArray().inputStream()).use { zip ->
            while(true) { val entry=zip.nextEntry ?: break;assertFalse(entry.name.contains("draft"));assertFalse(zip.readBytes().toString(Charsets.UTF_8).contains(value.request.sourceId)) }
        }
        assertTrue(service.publish(restored,restored.items).written)
    }
    @Test fun invalidPendingMetadataAndEmptySelectionsNeverPublish()=runBlocking {
        assertThrows(IllegalArgumentException::class.java) { AiSourceDrafts.pending(" ",AiRequest("s","topic"),AiProfile()) }
        val value=prepared();val service=AiSourceDrafts(repo);service.saveCheckpoint(value)
        try { service.publish(value,emptyList());fail("published unreviewed source") } catch(_: IllegalArgumentException) { }
        assertThrows(IllegalArgumentException::class.java) { AiSourceDrafts.validate(value.copy(pendingSource=value.pendingSource!!.copy(url="https://untrusted.example"))) }
        assertFalse(repo.dao.sources().any { it.sourceId==value.request.sourceId })
    }
}
