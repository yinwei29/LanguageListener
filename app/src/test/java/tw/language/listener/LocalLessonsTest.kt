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
import tw.language.listener.backup.*
import tw.language.listener.sync.SheetImporter
import java.io.ByteArrayOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],application=Application::class)
class LocalLessonsTest {
    private lateinit var repo: Repository
    @Before fun setup() = runBlocking {
        repo=Repository(Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Application>(),ListenerDatabase::class.java).allowMainThreadQueries().build())
        repo.seed()
    }
    @After fun teardown() { repo.db.close() }
    private fun lesson()=Item(Repository.LOCAL_SOURCE_ID,"stable-id",zh=" 自訂教材 ",en=" Custom lesson ",id="")
    @Test fun localCreationAndEditingKeepProgressAndExistingSession()=runBlocking {
        repo.saveLocalItem(lesson())
        assertEquals("自訂教材",repo.dao.item(Repository.LOCAL_SOURCE_ID,"stable-id")!!.zh)
        assertTrue(repo.dao.sources().single { it.sourceId==Repository.LOCAL_SOURCE_ID }.url.isEmpty())
        repo.updateProgress(Repository.LOCAL_SOURCE_ID,"stable-id") { it.copy(favorite=true,playCount=3) }
        repo.createSession(PlaybackOptions(excludeLearned=false))
        val queue=repo.dao.queue(); val session=repo.dao.session(); val progress=repo.dao.progress(Repository.LOCAL_SOURCE_ID,"stable-id")
        repo.saveLocalItem(lesson().copy(en="Edited lesson"))
        assertEquals("Edited lesson",repo.dao.item(Repository.LOCAL_SOURCE_ID,"stable-id")!!.en)
        assertEquals(1,repo.dao.sourcePage(Repository.LOCAL_SOURCE_ID,null,100).size)
        assertEquals(queue,repo.dao.queue()); assertEquals(session,repo.dao.session())
        assertEquals(progress,repo.dao.progress(Repository.LOCAL_SOURCE_ID,"stable-id"))
    }
    @Test fun invalidManualInputCannotOverwriteRemoteLessonsOrCreatePartialSource()=runBlocking {
        for(value in listOf(lesson().copy(en=""),lesson().copy(sourceId="remote"),lesson().copy(zh="x".repeat(100001)),lesson().copy(type="bad"))) {
            try { repo.saveLocalItem(value); fail("accepted invalid local input") } catch(_: IllegalArgumentException) { }
        }
        assertFalse(repo.dao.sources().any { it.sourceId==Repository.LOCAL_SOURCE_ID })
        assertEquals(5,repo.dao.itemPage(100,0).size)
    }
    @Test fun fullBackupRestoresManualLessonsAndProgressOffline()=runBlocking {
        val app=ApplicationProvider.getApplicationContext<Application>()
        repo.saveLocalItem(lesson()); repo.updateProgress(Repository.LOCAL_SOURCE_ID,"stable-id") { it.copy(learned=true) }
        val manager=BackupManager(repo,app.cacheDir)
        val out=ByteArrayOutputStream(); manager.export(true,out)
        repo.removeSource(Repository.LOCAL_SOURCE_ID)
        manager.preview(out.toByteArray().inputStream()).use { manager.restore(it,true) }
        assertEquals("Custom lesson",repo.dao.item(Repository.LOCAL_SOURCE_ID,"stable-id")!!.en)
        assertFalse(repo.dao.item(Repository.LOCAL_SOURCE_ID,"stable-id")!!.deleted)
        assertTrue(repo.dao.progress(Repository.LOCAL_SOURCE_ID,"stable-id")!!.learned)
    }
    @Test fun emptyCsvPreservesCachedLessonProgressAndLastSuccessfulSync()=runBlocking {
        val source=Source("remote","remote","https://docs.google.com/spreadsheets/d/example/export?format=csv&gid=0",lastSync=123)
        repo.dao.putSources(listOf(source)); repo.dao.putItems(listOf(Item("remote","existing",zh="既有",en="Existing",id="")))
        repo.updateProgress("remote","existing") { it.copy(favorite=true,playCount=5) }
        val before=repo.dao.progress("remote","existing")
        try { SheetImporter(repo).import(source,"".reader()); fail("accepted an uninitialized sheet") }
        catch(e: IllegalArgumentException) { assertEquals("CSV 為空",e.message) }
        assertFalse(repo.dao.item("remote","existing")!!.deleted)
        assertEquals(before,repo.dao.progress("remote","existing"))
        assertEquals(source,repo.dao.sources().single { it.sourceId=="remote" })
        assertNotNull(repo.dao.item("demo","hello"))
    }
}
