package tw.language.listener

import android.app.Application
import android.database.sqlite.SQLiteDatabase
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
import tw.language.listener.backup.*
import tw.language.listener.data.*
import tw.language.listener.sync.SheetImporter
import java.io.*
import java.security.MessageDigest
import java.util.zip.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],application=Application::class)
class AuthoringMigrationBackupTest {
    @Test fun v1MigrationKeepsStableKeysProgressAndCreatesDraftStorage() = runBlocking {
        val context=ApplicationProvider.getApplicationContext<Application>()
        val name="migration-${System.nanoTime()}.db"
        val file=context.getDatabasePath(name).apply { parentFile!!.mkdirs() }
        val schema=Json.parseToJsonElement(checkNotNull(javaClass.classLoader!!.getResourceAsStream("tw.language.listener.data.ListenerDatabase/1.json")).bufferedReader().use { it.readText() }).jsonObject["database"]!!.jsonObject
        SQLiteDatabase.openOrCreateDatabase(file,null).use { old ->
            schema["entities"]!!.jsonArray.forEach { entity ->
                val table=entity.jsonObject
                old.execSQL(table["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}",table["tableName"]!!.jsonPrimitive.content))
                table["indices"]!!.jsonArray.forEach { index -> old.execSQL(index.jsonObject["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}",table["tableName"]!!.jsonPrimitive.content)) }
            }
            schema["setupQueries"]?.jsonArray?.forEach { old.execSQL(it.jsonPrimitive.content) }
            old.execSQL("INSERT INTO sources VALUES ('stable-source','existing','',123,NULL)")
            old.execSQL("INSERT INTO progress VALUES ('stable-source','item-1',1,1,12345,2,8,999,888)")
            old.version=1
        }
        val db=Room.databaseBuilder(context,ListenerDatabase::class.java,name).addMigrations(Migrations.V1_TO_V2,Migrations.V2_TO_V3,Migrations.V3_TO_V4,Migrations.V4_TO_V5,Migrations.V5_TO_V6).allowMainThreadQueries().build()
        try {
            assertEquals("public",db.dao().sources().single().access)
            assertNull(db.dao().settings()?.aiProfile)
            val progress=db.dao().progress("stable-source","item-1")!!
            assertTrue(progress.learned); assertTrue(progress.favorite); assertEquals(8L,progress.playCount); assertEquals(12345L,progress.skipUntil)
            db.dao().putDraft(DraftRecord(payload="draft"))
            assertEquals(6,db.openHelper.readableDatabase.version)
        } finally { db.close(); context.deleteDatabase(name) }
    }
    @Test fun authorizedDownloadFeedsExistingImporterWithoutResettingProgress() = runBlocking {
        val context=ApplicationProvider.getApplicationContext<Application>()
        val db=Room.inMemoryDatabaseBuilder(context,ListenerDatabase::class.java).allowMainThreadQueries().build()
        val repo=Repository(db); val file=File.createTempFile("sheet", ".csv",context.cacheDir)
        try {
            val source=Source("s","private","https://docs.google.com/spreadsheets/d/example/export?format=csv&gid=7",access="google")
            repo.dao.putSources(listOf(source)); repo.updateProgress("s","existing") { it.copy(learned=true,favorite=true,reviewPriority=3,playCount=9) }
            val before=repo.dao.progress("s","existing")
            AuthoringFixture().use { api ->
                api.json("""{"sheets":[{"properties":{"sheetId":7,"title":"Lessons","gridProperties":{"rowCount":3,"columnCount":26}}}]}""")
                api.json("""{"values":[["ID","zh-TW","en-US","id-ID"]]}""")
                api.json("""{"valueRanges":[{"values":[["existing","你好","Hello","Halo"],["new","謝謝","Thanks","Terima kasih"]]}]}""")
                api.sheets.download(source,"test-token",file)
                file.reader().use { SheetImporter(repo).import(source,it) }
            }
            assertEquals(before,repo.dao.progress("s","existing"))
            assertEquals("google",repo.dao.sources().single().access)
            assertEquals(2,repo.createSession(PlaybackOptions(excludeLearned=false,count=20)))
        } finally { db.close(); file.delete() }
    }
    @Test fun zipV3PreservesLanguageExcludesCredentialsAndReadsLegacyV1AndV2() = runBlocking {
        val context=ApplicationProvider.getApplicationContext<Application>()
        val db=Room.inMemoryDatabaseBuilder(context,ListenerDatabase::class.java).allowMainThreadQueries().build()
        val repo=Repository(db); repo.seed()
        val cache=File(context.cacheDir,"backup-${System.nanoTime()}").apply { mkdirs() }
        val marker=File(context.noBackupFilesDir,"credential-test-marker").apply { parentFile!!.mkdirs(); writeText("TEST_SECRET_MUST_NOT_APPEAR_IN_ZIP") }
        val manager=BackupManager(repo,cache)
        try {
            repo.dao.putSources(listOf(Source("private","private","https://docs.google.com/spreadsheets/d/example/export?format=csv&gid=7",access="google")))
            val profile=AiProfile("compatible","https://provider.example/v1","my-model")
            repo.dao.putSettings(Settings(aiProfile=profile,uiLanguage="id"))
            val draft=AuthoringValidation.parse("""{"items":[{"zh-TW":"未審核","en-US":"Unreviewed","id-ID":"Belum ditinjau"}]}""",AiRequest("private","test",count=1))
            repo.dao.putDraft(DraftRecord(payload=JsonCodec.json.encodeToString(draft)))
            val out=ByteArrayOutputStream(); manager.export(true,out)
            val entries=linkedMapOf<String,ByteArray>()
            ZipInputStream(out.toByteArray().inputStream()).use { zip -> while(true) {
                val entry=zip.nextEntry ?: break; entries[entry.name]=zip.readBytes()
            } }
            assertFalse(entries.values.any { it.toString(Charsets.UTF_8).contains("TEST_SECRET_MUST_NOT_APPEAR_IN_ZIP") })
            assertFalse(entries.values.any { it.toString(Charsets.UTF_8).contains("Unreviewed") })
            manager.preview(out.toByteArray().inputStream()).use { preview ->
                assertEquals(7,preview.manifest.schemaVersion)
                assertEquals("google",preview.sources.first { it.sourceId=="private" }.access)
                manager.restore(preview,true)
                assertEquals(profile,repo.dao.settings()!!.aiProfile)
                assertEquals("id",repo.dao.settings()!!.uiLanguage)
            }
            // Rebuild legacy payloads without language; version 1 also lacks access/profile.
            entries["sources.jsonl"]=entries.getValue("sources.jsonl").toString(Charsets.UTF_8).lineSequence().filter(String::isNotBlank).joinToString("\n",postfix="\n") {
                JsonObject(Json.parseToJsonElement(it).jsonObject.filterKeys { key -> key!="access" }).toString()
            }.toByteArray()
            entries["settings.jsonl"]=entries.getValue("settings.jsonl").toString(Charsets.UTF_8).lineSequence().filter(String::isNotBlank).joinToString("\n",postfix="\n") {
                JsonObject(Json.parseToJsonElement(it).jsonObject.filterKeys { key -> key!="aiProfile" && key!="uiLanguage" }).toString()
            }.toByteArray()
            entries.remove("reviews.jsonl")
            val manifest=JsonCodec.json.decodeFromString(Manifest.serializer(),entries.getValue("manifest.json").toString(Charsets.UTF_8))
            val checksum=BackupArchive.payloadNames.filter { it in entries }.associateWith { key -> MessageDigest.getInstance("SHA-256").digest(entries.getValue(key)).joinToString("") { "%02x".format(it) } }
            for(version in 1..3) {
                entries["manifest.json"]=JsonCodec.json.encodeToString(manifest.copy(schemaVersion=version,checksum=checksum)).toByteArray()
                val legacy=ByteArrayOutputStream()
                ZipOutputStream(legacy).use { zip -> entries.forEach { (name,bytes) -> zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry() } }
                manager.preview(legacy.toByteArray().inputStream()).use { preview ->
                    assertEquals(version,preview.manifest.schemaVersion); assertTrue(preview.sources.all { it.access=="public" })
                    manager.restore(preview,true); assertNull(repo.dao.settings()!!.aiProfile); assertEquals("zh-TW",repo.dao.settings()!!.uiLanguage)
                }
            }
        } finally { db.close(); cache.deleteRecursively(); marker.delete() }
    }
}
