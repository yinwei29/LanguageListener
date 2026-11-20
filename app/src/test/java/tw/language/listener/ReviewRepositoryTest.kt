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
import tw.language.listener.data.*
import tw.language.listener.review.*
import tw.language.listener.backup.*
import tw.language.listener.sync.SheetImporter
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.time.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],application=Application::class)
class ReviewRepositoryTest {
    private lateinit var repo: Repository
    @Before fun setup()=runBlocking { repo=Repository(Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Application>(),ListenerDatabase::class.java).allowMainThreadQueries().build());repo.seed() }
    @After fun close() { repo.db.close() }
    private fun id()=UUID.randomUUID().toString()
    @Test fun ratingIsIdempotentAndHistoryCountsActualReviews()=runBlocking {
        val event=id();val now=System.currentTimeMillis()
        val first=repo.rate("demo","hello",Rating.GOOD,event,now)
        val second=repo.rate("demo","hello",Rating.GOOD,event,now+1000)
        assertEquals(first,second);assertEquals(1,repo.dao.reviewPage(100,0).size)
        val today=LocalDate.now().toEpochDay()
        assertEquals(1,repo.dao.reviewDays(today-6,today).first().single().total)
        repo.rate("demo","water",Rating.AGAIN,id(),now)
        assertEquals(1,repo.dao.reviewDays(today-6,today).first().single().again)
        assertEquals(2,repo.dao.dueSummary(now).first().scheduled)
    }
    @Test fun dueQueueIncludesLearnedButRespectsPausesDisabledAndFutureDates()=runBlocking {
        val past=System.currentTimeMillis()-3*86400000L
        repo.rate("demo","hello",Rating.GOOD,id(),past)
        repo.rate("demo","water",Rating.GOOD,id(),past)
        repo.rate("demo","thanks",Rating.EASY,id())
        repo.updateProgress("demo","hello") { it.copy(learned=true) }
        repo.updateProgress("demo","water") { it.copy(skipUntil=System.currentTimeMillis()+86400000L) }
        assertEquals(1,repo.dao.dueSummary(System.currentTimeMillis()).first().due)
        assertEquals(1,repo.createSession(PlaybackOptions(state="due",excludeLearned=false)))
        assertEquals("hello",repo.dao.queue().single().itemId)
        assertEquals(0,repo.createSession(PlaybackOptions(state="due",excludeLearned=true)))
        val hello=repo.dao.item("demo","hello")!!;repo.dao.putItems(listOf(hello.copy(enabled=false)))
        assertEquals(0,repo.dao.dueSummary(System.currentTimeMillis()).first().due)
    }
    @Test fun sheetsChangesKeepReviewScheduleAndHistory()=runBlocking {
        val source=Source("s","test","https://docs.google.com/spreadsheets/d/example/export?format=csv&gid=0")
        repo.dao.putSources(listOf(source));repo.dao.putItems(listOf(Item("s","one",zh="一",en="one",id="satu")))
        repo.rate("s","one",Rating.GOOD,id());val old=repo.dao.progress("s","one")
        SheetImporter(repo).import(source,"ID,zh-TW,en-US,id-ID\none,一,changed,satu\n".reader())
        assertEquals(old,repo.dao.progress("s","one"));assertEquals(1,repo.dao.reviewPage(100,0).size)
    }
    @Test fun backupRoundTripAndRepeatedMergeDoNotDuplicateReviewEvents()=runBlocking {
        val app=ApplicationProvider.getApplicationContext<Application>();val backup=BackupManager(repo,app.cacheDir)
        repo.rate("demo","hello",Rating.GOOD,id());repo.rate("demo","water",Rating.AGAIN,id())
        val old=repo.dao.progress("demo","hello");val out=ByteArrayOutputStream();backup.export(true,out)
        backup.preview(out.toByteArray().inputStream()).use { preview ->
            assertEquals(2,preview.manifest.reviewCount);backup.restore(preview,true)
        }
        repeat(2) { backup.preview(out.toByteArray().inputStream()).use { backup.restore(it,false) } }
        assertEquals(2,repo.dao.reviewPage(100,0).size);assertEquals(old,repo.dao.progress("demo","hello"))
    }
    @Test fun conflictingHistoryMergeRollsBackInsteadOfOverwriting()=runBlocking {
        val app=ApplicationProvider.getApplicationContext<Application>();val manager=BackupManager(repo,app.cacheDir)
        val event=id();repo.rate("demo","hello",Rating.GOOD,event)
        val original=repo.dao.reviewEvent(event)!!;val out=ByteArrayOutputStream();manager.export(true,out)
        repo.dao.clearReviews();repo.dao.putReview(original.copy(rating=Rating.HARD))
        repo.updateProgress("demo","hello") { it.copy(favorite=true) }
        val before=repo.dao.progress("demo","hello")
        manager.preview(out.toByteArray().inputStream()).use { preview ->
            try { manager.restore(preview,false);fail("overwrote conflicting event") } catch(_: IllegalArgumentException) { }
        }
        assertEquals(before,repo.dao.progress("demo","hello"));assertEquals(Rating.HARD,repo.dao.reviewEvent(event)!!.rating)
    }

}
