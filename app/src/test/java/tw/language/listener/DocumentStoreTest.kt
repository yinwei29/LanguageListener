package tw.language.listener

import android.app.Application
import android.content.*
import android.content.pm.ProviderInfo
import android.database.*
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import tw.language.listener.backup.BackupManager
import tw.language.listener.data.*
import tw.language.listener.documents.*
import java.io.*
import java.util.zip.ZipInputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],application=Application::class)
class DocumentStoreTest {
    private lateinit var context: Application
    private lateinit var repo: Repository
    private lateinit var file: File
    private lateinit var store: DocumentStore
    private val uri=Uri.parse("content://lesson.test/lessons.csv")
    class Provider(private val file: File): ContentProvider() {
        override fun onCreate()=true
        override fun getType(uri: Uri)="text/csv"
        override fun query(uri: Uri,projection: Array<out String>?,selection: String?,selectionArgs: Array<out String>?,sortOrder: String?): Cursor = MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME)).apply { addRow(arrayOf("lessons.csv")) }
        override fun openFile(uri: Uri,mode: String): ParcelFileDescriptor = ParcelFileDescriptor.open(file,ParcelFileDescriptor.parseMode(mode))
        override fun insert(uri: Uri,values: ContentValues?): Uri?=null
        override fun delete(uri: Uri,selection: String?,selectionArgs: Array<out String>?)=0
        override fun update(uri: Uri,values: ContentValues?,selection: String?,selectionArgs: Array<out String>?)=0
    }
    @Before fun setup()=runBlocking {
        context=ApplicationProvider.getApplicationContext();repo=Repository(Room.inMemoryDatabaseBuilder(context,ListenerDatabase::class.java).allowMainThreadQueries().build());repo.seed()
        file=File.createTempFile("lessons", ".csv",context.cacheDir)
        val provider=Provider(file);provider.attachInfo(context,ProviderInfo().apply { authority="lesson.test";grantUriPermissions=true })
        ShadowContentResolver.registerProviderInternal("lesson.test",provider)
        store=DocumentStore(context,repo)
    }
    @After fun close() { repo.db.close();file.delete() }
    @Test fun createInitializeEditReadBackAndPreserveProgressThroughContentResolver()=runBlocking {
        val plan=store.prepare(uri);assertEquals(13,plan.missing.size);store.initialize(plan)
        val id=plan.source.sourceId;assertEquals("file",repo.dao.sources().single { it.sourceId==id }.access)
        val item=Item(id,"stable",zh="你好",en="Hello",id="Halo")
        repo.saveLocalItem(item);repo.updateProgress(id,"stable") { it.copy(favorite=true,playCount=9) }
        store.commit(store.prepareSync(plan.source))
        assertEquals("Hello",DocumentCodec.decode(file.readBytes()).items(id).single().en)
        val external=DocumentCodec.decode(file.readBytes());external.update(listOf(item.copy(en="From Excel")));file.writeBytes(external.encode())
        store.commit(store.prepareSync(plan.source))
        assertEquals("From Excel",repo.dao.item(id,"stable")!!.en)
        assertTrue(repo.dao.progress(id,"stable")!!.favorite);assertEquals(9L,repo.dao.progress(id,"stable")!!.playCount)
    }
    @Test fun fileChangedAfterPreviewIsNotOverwrittenAndConflictKeepsBothSides()=runBlocking {
        val plan=store.prepare(uri);store.initialize(plan);val id=plan.source.sourceId
        val item=Item(id,"stable",zh="一",en="one",id="satu")
        repo.saveLocalItem(item);store.commit(store.prepareSync(plan.source))
        val preview=store.prepareSync(plan.source)
        val table=DocumentCodec.decode(file.readBytes());table.update(listOf(item.copy(en="external")));file.writeBytes(table.encode())
        val edited=file.readBytes()
        try { store.commit(preview);fail("overwrote changed file") } catch(_: IllegalStateException) { }
        assertArrayEquals(edited,file.readBytes())
        repo.saveLocalItem(item.copy(en="app edit"))
        try { store.prepareSync(plan.source);fail("ignored conflicting edits") } catch(_: IllegalStateException) { }
        assertEquals("app edit",repo.dao.item(id,"stable")!!.en);assertArrayEquals(edited,file.readBytes())
    }
    @Test fun zipKeepsFileLessonContentsButExcludesUriPermissionsAndMergeBases()=runBlocking {
        val plan=store.prepare(uri);store.initialize(plan)
        repo.saveLocalItem(Item(plan.source.sourceId,"stable",zh="一",en="one",id="satu"))
        val backup=BackupManager(repo,context.cacheDir);val out=ByteArrayOutputStream();backup.export(true,out)
        ZipInputStream(out.toByteArray().inputStream()).use { zip -> while(true) { zip.nextEntry ?: break;assertFalse(zip.readBytes().toString(Charsets.UTF_8).contains("content://lesson.test")) } }
        backup.preview(out.toByteArray().inputStream()).use { backup.restore(it,true) }
        assertTrue(repo.dao.documents().isEmpty())
        assertEquals("file",repo.dao.sources().single { it.sourceId==plan.source.sourceId }.access)
        assertEquals("one",repo.dao.item(plan.source.sourceId,"stable")!!.en)
    }
    @Test fun readOnlyPermissionStopsSyncBeforeFileWrite()=runBlocking {
        val plan=store.prepare(uri);store.initialize(plan)
        val before=file.readBytes()
        context.contentResolver.releasePersistableUriPermission(uri,Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        try { store.prepareSync(plan.source);fail("accepted read-only file") } catch(_: IllegalStateException) { }
        assertArrayEquals(before,file.readBytes())
    }

    @Test fun removingAndReconnectingFileRestoresSameKeysAndProgress()=runBlocking {
        val plan=store.prepare(uri);store.initialize(plan);val id=plan.source.sourceId
        repo.saveLocalItem(Item(id,"stable",zh="一",en="one",id="satu"));store.commit(store.prepareSync(plan.source))
        repo.updateProgress(id,"stable") { it.copy(favorite=true) }
        repo.removeSource(id)
        val reconnected=store.prepare(uri);assertEquals(id,reconnected.source.sourceId);store.initialize(reconnected)
        assertFalse(repo.dao.item(id,"stable")!!.deleted);assertTrue(repo.dao.progress(id,"stable")!!.favorite)
    }

    @Test fun attachingExistingSourceDoesNotRevivePreviouslyDeletedRemoteRows()=runBlocking {
        val source=Source("remote","existing","https://docs.google.com/spreadsheets/d/example/export?format=csv&gid=0")
        repo.dao.putSources(listOf(source))
        repo.dao.putItems(listOf(Item("remote","active",zh="一",en="one",id="satu"),Item("remote","removed",zh="舊",en="old",id="lama",deleted=true)))
        val plan=store.prepare(uri,"remote");store.initialize(plan)
        assertEquals(listOf("active"),DocumentCodec.decode(file.readBytes()).items("remote").map { it.itemId })
        assertTrue(repo.dao.item("remote","removed")!!.deleted)
    }

}
