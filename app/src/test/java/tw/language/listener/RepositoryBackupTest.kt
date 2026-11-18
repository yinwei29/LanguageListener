package tw.language.listener

import android.app.Application
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import tw.language.listener.backup.*
import tw.language.listener.data.*
import tw.language.listener.sync.SheetImporter
import java.io.*
import java.time.*
import java.util.zip.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],application=Application::class)
class RepositoryBackupTest {
    private lateinit var db: ListenerDatabase
    private lateinit var repo: Repository
    private lateinit var manager: BackupManager
    private lateinit var cache: File
    @Before fun setup() {
        val context=ApplicationProvider.getApplicationContext<Application>()
        db=Room.inMemoryDatabaseBuilder(context,ListenerDatabase::class.java).allowMainThreadQueries().build()
        repo=Repository(db); cache=File(context.cacheDir,"test-${System.nanoTime()}").apply { mkdirs() }
        manager=BackupManager(repo,cache)
        runBlocking { repo.seed() }
    }
    @After fun close() { db.close(); cache.deleteRecursively() }

    @Test fun eligibilityStableProgressAndStalePlayerWrites() = runBlocking {
        repo.updateProgress("demo","hello") { it.copy(learned=true,favorite=true) }
        repo.updateProgress("demo","water") { it.copy(skipUntil=System.currentTimeMillis()+86_400_000) }
        assertEquals(3,repo.createSession(PlaybackOptions(count=100,random=true)))
        val keys=repo.dao.queue().map { it.sourceId to it.itemId }
        assertEquals(keys.size,keys.distinct().size)
        assertFalse(keys.any { it.second in listOf("hello","water") })
        val session=repo.dao.session()!!
        repo.savePosition(2,250,session.sessionId)
        assertEquals(250,repo.dao.session()!!.offsetMs)
        repo.createSession(PlaybackOptions(count=1))
        repo.savePosition(99,999,session.sessionId)
        assertEquals(0,repo.dao.session()!!.position)
        val old=repo.dao.item("demo","hello")!!
        db.withTransaction { repo.dao.softDeleteSource("demo"); repo.dao.putItems(listOf(old.copy(en="Hello again"))) }
        assertTrue(repo.dao.progress("demo","hello")!!.learned)
        assertTrue(repo.dao.progress("demo","hello")!!.favorite)
        assertEquals("Hello again",repo.dao.item("demo","hello")!!.en)
        try {
            db.withTransaction { repo.dao.softDeleteSource("demo"); error("CSV row rejected") }
        } catch(_: IllegalStateException) { }
        assertFalse(repo.dao.item("demo","hello")!!.deleted)
    }
    @Test fun fullRoundTripAndLatestProgressMerge() = runBlocking {
        repo.recordPlay("demo","hello")
        repo.updateProgress("demo","hello") { it.copy(learned=true,favorite=true,playCount=4) }
        repo.createSession(PlaybackOptions(count=2))
        val session=repo.dao.session()!!
        repo.savePosition(1,420,session.sessionId)
        val output=ByteArrayOutputStream(); manager.export(true,output)
        val preview=manager.preview(output.toByteArray().inputStream())
        assertEquals(5,preview.manifest.itemCount)
        assertEquals(1,preview.manifest.progressCount)
        repo.updateProgress("demo","hello") { it.copy(learned=false,playCount=8,modifiedAt=Long.MAX_VALUE) }
        // Timestamp winner is deterministic, including ties retaining local data.
        val latest=repo.dao.progress("demo","hello")!!.copy(modifiedAt=Long.MAX_VALUE)
        repo.dao.putProgress(listOf(latest))
        manager.restore(preview,false)
        assertFalse(repo.dao.progress("demo","hello")!!.learned)
        assertEquals(8,repo.dao.progress("demo","hello")!!.playCount)
        val epoch=repo.syncEpoch.get()
        manager.restore(preview,true)
        assertTrue(repo.syncEpoch.get()>epoch)
        assertTrue(repo.dao.progress("demo","hello")!!.learned)
        assertEquals(4,repo.dao.progress("demo","hello")!!.playCount)
        assertEquals(5,repo.dao.itemPage(100,0).size)
        assertEquals(420,repo.dao.session()!!.offsetMs)
        assertEquals(2,repo.dao.queue().size)
        assertEquals(1,repo.dao.days().size)
        preview.close()
    }
    @Test fun csvSyncIsAtomicDeduplicatesAndPreservesProgress() = runBlocking {
        val source=repo.dao.sources().single()
        repo.updateProgress("demo","hello") { it.copy(learned=true,favorite=true,skipUntil=123,reviewPriority=2,playCount=9) }
        val before=repo.dao.progress("demo","hello")
        SheetImporter(repo).import(source,"ID,zh-TW,en-US,id-ID\nhello,你好,Hello changed,Halo\nhello,你好,Latest,Halo\nnew,新,New,Baru\n".reader())
        assertEquals("Latest",repo.dao.item("demo","hello")!!.en)
        assertEquals(before,repo.dao.progress("demo","hello"))
        assertTrue(repo.dao.item("demo","thanks")!!.deleted)
        assertTrue(repo.dao.sources().single().lastSync>0)
        try {
            SheetImporter(repo).import(repo.dao.sources().single(),"ID,zh-TW,en-US,id-ID\nnew,新,Overwrite,Baru\n,空白 ID,Invalid,Salah\n".reader())
            fail("invalid row accepted")
        } catch(_: IllegalArgumentException) { }
        assertEquals("New",repo.dao.item("demo","new")!!.en)
        assertFalse(repo.dao.item("demo","hello")!!.deleted)
        assertEquals(before,repo.dao.progress("demo","hello"))
        repo.removeSource("demo")
        SheetImporter(repo).import(source,"ID,zh-TW,en-US,id-ID\nhello,你好,Resurrect,Halo".reader())
        assertTrue(repo.dao.sources().isEmpty())
    }
    @Test fun lightweightRoundTripKeepsOrphanProgressUntilSync() = runBlocking {
        repo.updateProgress("demo","thanks") { it.copy(favorite=true,skipUntil=12345,reviewPriority=1) }
        val out=ByteArrayOutputStream(); manager.export(false,out)
        val preview=manager.preview(out.toByteArray().inputStream())
        assertFalse(preview.manifest.full); assertEquals(0,preview.manifest.itemCount)
        manager.restore(preview,true)
        assertTrue(repo.dao.itemPage(100,0).isEmpty())
        assertTrue(repo.dao.progress("demo","thanks")!!.favorite)
        assertEquals(12345,repo.dao.progress("demo","thanks")!!.skipUntil)
        preview.close()
    }
    @Test fun databaseReopenPreservesQueueOptionsPositionAndProgress() = runBlocking {
        val context=ApplicationProvider.getApplicationContext<Application>()
        val name="persistent-${System.nanoTime()}.db"
        fun open()=Room.databaseBuilder(context,ListenerDatabase::class.java,name).allowMainThreadQueries().build()
        var disk=open()
        try {
            val first=Repository(disk); first.seed()
            val options=PlaybackOptions(count=3,random=true,target="id-ID",gapSeconds=3,repeats=2)
            first.createSession(options)
            first.recordPlay("demo","hello")
            first.updateProgress("demo","hello") { it.copy(favorite=true,reviewPriority=2) }
            first.saveSettings(Settings(playback=options,wifiOnly=true))
            first.savePosition(1,3000,first.dao.session()!!.sessionId)
            val queue=first.dao.queue()
            disk.close(); disk=open()
            val second=Repository(disk); second.seed()
            assertEquals(queue,second.dao.queue())
            assertEquals(options,second.dao.session()!!.options)
            assertEquals(3000L,second.dao.session()!!.offsetMs)
            assertTrue(second.dao.progress("demo","hello")!!.favorite)
            assertEquals(1L,second.dao.progress("demo","hello")!!.playCount)
            assertEquals(1,second.dao.days().size)
            assertTrue(second.dao.settings()!!.wifiOnly)
        } finally { disk.close(); context.deleteDatabase(name) }
    }
    @Test fun removingAndReaddingSameSheetKeepsStableKey() = runBlocking {
        val url="https://docs.google.com/spreadsheets/d/example-id/edit#gid=42"
        repo.addSource("測試來源",url)
        val old=repo.dao.sources().first { it.url.isNotEmpty() }
        repo.updateProgress(old.sourceId,"unchanged") { it.copy(favorite=true) }
        repo.removeSource(old.sourceId)
        repo.addSource("重新新增",url)
        assertEquals(old.sourceId,repo.dao.sources().first { it.url.isNotEmpty() }.sourceId)
        assertTrue(repo.dao.progress(old.sourceId,"unchanged")!!.favorite)
    }
    @Test fun rejectsTraversalDuplicatesCorruptionVersionAndZipBomb() = runBlocking {
        fun zip(entries: List<Pair<String,ByteArray>>): ByteArray {
            val out=ByteArrayOutputStream()
            ZipOutputStream(out).use { zip -> entries.forEach { (name,data) -> zip.putNextEntry(ZipEntry(name)); zip.write(data); zip.closeEntry() } }
            return out.toByteArray()
        }
        for(name in listOf("../outside", "/absolute", "folder/../../outside", "folder\\outside", "unknown.json")) {
            try { manager.preview(zip(listOf(name to byteArrayOf(1))).inputStream()); fail("accepted path $name") }
            catch(_: IllegalArgumentException) { }
        }
        val out=ByteArrayOutputStream(); manager.export(true,out)
        val entries=mutableListOf<Pair<String,ByteArray>>()
        ZipInputStream(out.toByteArray().inputStream()).use { zip ->
            while(true) { val entry=zip.nextEntry ?: break; entries+=entry.name to zip.readBytes() }
        }
        val corrupt=entries.map { (name,data) -> name to if(name=="items.jsonl") "garbage".toByteArray() else data }
        try { manager.preview(zip(corrupt).inputStream()); fail("accepted checksum mismatch") } catch(_: IllegalArgumentException) { }
        val duplicate=out.toByteArray().copyOf()
        val from="sources.jsonl".toByteArray(); val to="session.jsonl".toByteArray()
        for(i in 0..duplicate.size-from.size) {
            if(from.indices.all { duplicate[i+it]==from[it] }) to.copyInto(duplicate,i)
        }
        try { manager.preview(duplicate.inputStream()); fail("accepted duplicate entry") } catch(_: IllegalArgumentException) { }
        val version=entries.map { (name,data) -> name to if(name=="manifest.json") data.toString(Charsets.UTF_8).replace("\"schemaVersion\":3","\"schemaVersion\":99").toByteArray() else data }
        try { manager.preview(zip(version).inputStream()); fail("accepted future schema") } catch(_: IllegalArgumentException) { }
        val bomb=ByteArrayOutputStream()
        ZipOutputStream(bomb).use { zip ->
            zip.putNextEntry(ZipEntry("items.jsonl")); val zeros=ByteArray(1024*1024)
            repeat(129) { zip.write(zeros) }; zip.closeEntry()
        }
        try { manager.preview(bomb.toByteArray().inputStream()); fail("accepted zip bomb") } catch(_: IllegalArgumentException) { }
        assertEquals(5,repo.dao.itemPage(100,0).size)
        assertTrue(cache.listFiles().orEmpty().isEmpty())
    }
    @Test fun streakUsesLocalDatesAndAllowsYesterday() {
        val today=LocalDate.of(2026,11,15)
        fun day(offset: Int)=today.minusDays(offset.toLong()).toEpochDay()
        assertEquals(3,Repository.streakDays(listOf(day(0),day(0),day(1),day(2)),today))
        assertEquals(2,Repository.streakDays(listOf(day(1),day(2),day(4)),today))
        assertEquals(0,Repository.streakDays(listOf(day(3)),today))
    }
}
