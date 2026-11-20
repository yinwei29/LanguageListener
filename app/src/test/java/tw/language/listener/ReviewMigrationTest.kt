package tw.language.listener

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import tw.language.listener.data.*
import tw.language.listener.backup.*
import tw.language.listener.review.Rating
import java.io.*
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],application=Application::class)
class ReviewMigrationTest {
    private val app get()=ApplicationProvider.getApplicationContext<Application>()
    @Test fun v4DatabaseMigratesWithoutResettingQueueProgressFilesOrDraft()=runBlocking {
        val name="srs-migration-${System.nanoTime()}.db";val file=app.getDatabasePath(name).apply { parentFile!!.mkdirs() }
        val schema=Json.parseToJsonElement(javaClass.classLoader!!.getResourceAsStream("tw.language.listener.data.ListenerDatabase/4.json")!!.bufferedReader().use { it.readText() }).jsonObject["database"]!!.jsonObject
        SQLiteDatabase.openOrCreateDatabase(file,null).use { old ->
            schema["entities"]!!.jsonArray.forEach { entity -> val e=entity.jsonObject
                old.execSQL(e["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}",e["tableName"]!!.jsonPrimitive.content))
                e["indices"]!!.jsonArray.forEach { index -> old.execSQL(index.jsonObject["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}",e["tableName"]!!.jsonPrimitive.content)) }
            }
            schema["setupQueries"]!!.jsonArray.forEach { old.execSQL(it.jsonPrimitive.content) }
            old.execSQL("INSERT INTO sources VALUES ('stable','file','',123,NULL,'file')")
            old.execSQL("INSERT INTO progress VALUES ('stable','one',1,1,0,3,7,999,1000)")
            old.execSQL("INSERT INTO queue VALUES (1,'stable','one')")
            old.execSQL("INSERT INTO local_documents VALUES ('stable','content://test/lesson.csv','','hash','[]','[]')")
            old.execSQL("INSERT INTO ai_drafts VALUES (1,'draft')");old.version=4
        }
        val db=Room.databaseBuilder(app,ListenerDatabase::class.java,name).addMigrations(Migrations.V4_TO_V5).allowMainThreadQueries().build()
        try {
            val p=db.dao().progress("stable","one")!!
            assertTrue(p.favorite);assertTrue(p.learned);assertEquals(7L,p.playCount);assertEquals(0L,p.nextReviewAt);assertEquals(2.5,p.ease,0.0)
            assertEquals("one",db.dao().queue().single().itemId);assertEquals("content://test/lesson.csv",db.dao().documents().single().uri)
            assertEquals("draft",db.dao().observeDraft().first()!!.payload)
            db.dao().putReview(ReviewEvent(UUID.randomUUID().toString(),"stable","one",123,1,Rating.GOOD))
            assertEquals(1,db.dao().reviewPage(10,0).size)
        } finally { db.close();app.deleteDatabase(name) }
    }
    private fun unpack(bytes: ByteArray): LinkedHashMap<String,ByteArray> {
        val entries=linkedMapOf<String,ByteArray>();ZipInputStream(bytes.inputStream()).use { zip -> while(true) { val e=zip.nextEntry ?: break;entries[e.name]=zip.readBytes() } };return entries
    }
    private fun pack(entries: LinkedHashMap<String,ByteArray>,version: Int): ByteArray {
        val old=JsonCodec.json.decodeFromString<Manifest>(entries.getValue("manifest.json").toString(Charsets.UTF_8))
        val hashes=entries.filterKeys { it!="manifest.json" }.mapValues { MessageDigest.getInstance("SHA-256").digest(it.value).joinToString("") { b -> "%02x".format(b) } }
        entries["manifest.json"]=JsonCodec.json.encodeToString(old.copy(schemaVersion=version,checksum=hashes)).toByteArray()
        val out=ByteArrayOutputStream();ZipOutputStream(out).use { zip -> entries.forEach { (name,data) -> zip.putNextEntry(ZipEntry(name));zip.write(data);zip.closeEntry() } };return out.toByteArray()
    }
    @Test fun realV5PayloadWithoutReviewFileOrProgressFieldsRestoresWithDefaults()=runBlocking {
        val db=Room.inMemoryDatabaseBuilder(app,ListenerDatabase::class.java).allowMainThreadQueries().build();val repo=Repository(db);repo.seed()
        try {
            repo.updateProgress("demo","hello") { it.copy(favorite=true,learned=true,playCount=9) }
            val manager=BackupManager(repo,app.cacheDir);val out=ByteArrayOutputStream();manager.export(true,out);val entries=unpack(out.toByteArray())
            entries.remove("reviews.jsonl")
            entries["progress.jsonl"]=entries.getValue("progress.jsonl").toString(Charsets.UTF_8).lineSequence().filter(String::isNotBlank).joinToString("\n",postfix="\n") { line -> JsonObject(Json.parseToJsonElement(line).jsonObject.filterKeys { it !in listOf("nextReviewAt","lastReviewedAt","intervalDays","ease","repetitions","lapses") }).toString() }.toByteArray()
            repo.updateProgress("demo","hello") { it.copy(favorite=false) }
            repo.rate("demo","hello",Rating.GOOD,UUID.randomUUID().toString())
            val scheduled=repo.dao.progress("demo","hello")!!
            entries["progress.jsonl"]=entries.getValue("progress.jsonl").toString(Charsets.UTF_8).lineSequence().filter(String::isNotBlank).joinToString("\n",postfix="\n") { line ->
                JsonObject(Json.parseToJsonElement(line).jsonObject+("modifiedAt" to JsonPrimitive(System.currentTimeMillis()+10000))).toString()
            }.toByteArray()
            manager.preview(pack(entries,5).inputStream()).use { manager.restore(it,false) }
            val merged=repo.dao.progress("demo","hello")!!
            assertTrue(merged.favorite);assertEquals(scheduled.nextReviewAt,merged.nextReviewAt);assertEquals(scheduled.ease,merged.ease,0.0)
            assertEquals(1,repo.dao.reviewPage(10,0).size)
            manager.preview(pack(entries,5).inputStream()).use { manager.restore(it,true) }
            val p=repo.dao.progress("demo","hello")!!;assertEquals(9L,p.playCount);assertTrue(p.favorite);assertTrue(p.learned);assertEquals(0L,p.nextReviewAt);assertTrue(repo.dao.reviewPage(10,0).isEmpty())
        } finally { db.close() }
    }
    @Test fun malformedReviewStateIsRejectedBeforeDatabaseChanges()=runBlocking {
        val db=Room.inMemoryDatabaseBuilder(app,ListenerDatabase::class.java).allowMainThreadQueries().build();val repo=Repository(db);repo.seed()
        try {
            repo.rate("demo","hello",Rating.GOOD,UUID.randomUUID().toString());val old=repo.dao.progress("demo","hello")
            val manager=BackupManager(repo,app.cacheDir);val out=ByteArrayOutputStream();manager.export(true,out);val entries=unpack(out.toByteArray())
            entries["progress.jsonl"]=entries.getValue("progress.jsonl").toString(Charsets.UTF_8).replace("\"ease\":2.5","\"ease\":4.0").toByteArray()
            try { manager.preview(pack(entries,6).inputStream());fail("accepted invalid review state") } catch(_: IllegalArgumentException) { }
            assertEquals(old,repo.dao.progress("demo","hello"))
        } finally { db.close() }
    }
    @Test fun duplicateHistoryInArchiveIsRejectedWithoutChangingProgress()=runBlocking {
        val db=Room.inMemoryDatabaseBuilder(app,ListenerDatabase::class.java).allowMainThreadQueries().build();val repo=Repository(db);repo.seed()
        try {
            repo.rate("demo","hello",Rating.GOOD,UUID.randomUUID().toString());val old=repo.dao.progress("demo","hello")
            val manager=BackupManager(repo,app.cacheDir);val out=ByteArrayOutputStream();manager.export(true,out);val entries=unpack(out.toByteArray())
            val reviews=entries.getValue("reviews.jsonl");entries["reviews.jsonl"]=reviews+reviews
            try { manager.preview(pack(entries,6).inputStream());fail("accepted duplicate history") }
            catch(e: IllegalArgumentException) { assertEquals("複習紀錄重複",e.message) }
            assertEquals(old,repo.dao.progress("demo","hello"));assertEquals(1,repo.dao.reviewPage(10,0).size)
        } finally { db.close() }
    }

}
