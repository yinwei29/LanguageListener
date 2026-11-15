package tw.language.listener

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import tw.language.listener.data.*
import tw.language.listener.sync.SheetImporter
import java.io.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],application=Application::class)
class SyncScaleTest {
    private lateinit var repo: Repository
    @Before fun setup() {
        val context=ApplicationProvider.getApplicationContext<Application>()
        repo=Repository(Room.inMemoryDatabaseBuilder(context,ListenerDatabase::class.java).allowMainThreadQueries().build())
        runBlocking { repo.seed() }
    }
    @After fun close() { repo.db.close() }
    private fun csv(count: Int, changed: Boolean=false): Reader = buildString {
        appendLine("ID,zh-TW,en-US,id-ID")
        repeat(count) { appendLine("$it,教材 $it,${if(changed && it==0) "Changed" else "Text $it"},Teks $it") }
    }.reader()
    private fun writes(): Int = repo.db.openHelper.writableDatabase.query("SELECT COUNT(*) FROM test_writes").use { it.moveToFirst(); it.getInt(0) }

    @Test fun tenThousandRowsUpdateOnlyChangedTextAndPreserveProgress() = runBlocking {
        val source=Source("large","效能測試","https://docs.google.com/spreadsheets/d/example/export?format=csv&gid=0")
        repo.dao.putSources(listOf(source))
        val importer=SheetImporter(repo)
        val elapsed=kotlin.system.measureTimeMillis { assertEquals(10000,importer.import(source,csv(10000))!!.upserted) }
        println("10,000-row initial import: ${elapsed}ms")
        repo.updateProgress("large","0") { it.copy(learned=true,favorite=true,reviewPriority=1,playCount=42) }
        val progress=repo.dao.progress("large","0")
        val sql=repo.db.openHelper.writableDatabase
        sql.execSQL("CREATE TABLE test_writes(id INTEGER PRIMARY KEY)")
        sql.execSQL("CREATE TRIGGER count_item_updates AFTER UPDATE ON items BEGIN INSERT INTO test_writes(id) VALUES (NULL); END")
        val unchanged=importer.import(repo.dao.sources().first { it.sourceId=="large" },csv(10000))!!
        assertEquals(0,unchanged.upserted); assertEquals(0,unchanged.deleted); assertEquals(0,writes())
        val delta=importer.import(repo.dao.sources().first { it.sourceId=="large" },csv(9999,true))!!
        assertEquals(1,delta.upserted); assertEquals(1,delta.deleted); assertEquals(2,writes())
        assertEquals(progress,repo.dao.progress("large","0"))
        assertTrue(repo.dao.item("large","9999")!!.deleted)
        assertFalse(repo.dao.item("demo","hello")!!.deleted)
        assertEquals(250,repo.dao.search("","",250,0).size)
    }
    @Test fun errorAfterBatchRollbackAndNextImportStillWorks() = runBlocking {
        val source=repo.dao.sources().single()
        val before=repo.dao.itemPage(100,0)
        val malformed=csv(300).readText()+",無效,Invalid,Salah\n"
        try { SheetImporter(repo).import(source,malformed.reader()); fail("invalid accepted") } catch(_: IllegalArgumentException) { }
        assertEquals(before,repo.dao.itemPage(100,0))
        assertEquals(2,SheetImporter(repo).import(source,csv(2))!!.upserted)
        assertEquals(2,repo.dao.search("","",100,0).size)
    }
    @Test fun duplicatesAcrossBatchBoundaryEmptySnapshotAndResurrection() = runBlocking {
        val source=repo.dao.sources().single()
        val input=csv(251).readText()+"0,最新版,Latest,Terbaru\n"
        val importer=SheetImporter(repo)
        importer.import(source,input.reader())
        assertEquals("Latest",repo.dao.item("demo","0")!!.en)
        val empty=importer.import(repo.dao.sources().single(),"ID,zh-TW,en-US,id-ID\n".reader())!!
        assertEquals(251,empty.deleted)
        assertEquals(0,repo.dao.search("","",100,0).size)
        importer.import(repo.dao.sources().single(),csv(1))
        assertFalse(repo.dao.item("demo","0")!!.deleted)
    }
    @Test fun removedReaddedSourceRejectsOldResponseEvenWithSameMetadata() = runBlocking {
        val url="https://docs.google.com/spreadsheets/d/example/edit"
        repo.addSource("同名來源",url)
        val source=repo.dao.sources().first { it.url.isNotEmpty() }
        val epoch=repo.syncEpoch.get()
        repo.removeSource(source.sourceId); repo.addSource("同名來源",url)
        assertEquals(source,repo.dao.sources().first { it.sourceId==source.sourceId })
        assertNull(SheetImporter(repo).import(source,csv(1),epoch))
        assertNull(repo.dao.item(source.sourceId,"0"))
    }
}
