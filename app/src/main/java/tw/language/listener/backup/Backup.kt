package tw.language.listener.backup

import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import tw.language.listener.data.*
import tw.language.listener.sync.SheetUrl
import java.io.*
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

@Serializable
data class Manifest(val schemaVersion: Int = 4, val createdAt: Long, val appVersion: String = tw.language.listener.BuildConfig.VERSION_NAME,
    val full: Boolean, val itemCount: Int, val progressCount: Int, val sourceCount: Int,
    val checksum: Map<String,String>, val dayCount: Int = 0)
class BackupPreview(val directory: File, val manifest: Manifest, val sources: List<Source>) : Closeable {
    override fun close() { directory.deleteRecursively() }
}

/** Nothing is extracted by name; only these flat, allowlisted entries can enter staging. */
object BackupArchive {
    const val MAX_BYTES=128L*1024*1024
    val payloadNames=setOf("sources.jsonl","items.jsonl","progress.jsonl","queue.jsonl","session.jsonl","settings.jsonl","days.jsonl")
    fun digest(file: File): String {
        val md=MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer=ByteArray(8192)
            while(true) { val count=input.read(buffer); if(count<0) break; md.update(buffer,0,count) }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
    fun read(input: InputStream, parent: File): BackupPreview {
        val dir=java.nio.file.Files.createTempDirectory(parent.toPath(),"restore-").toFile()
        try {
            val seen=mutableSetOf<String>()
            var total=0L
            ZipInputStream(BufferedInputStream(input)).use { zip ->
                while(true) {
                    val entry=zip.nextEntry ?: break
                    require(entry.name in payloadNames || entry.name=="manifest.json") { "備份含有非法路徑或未知檔案" }
                    require(!entry.isDirectory && seen.add(entry.name)) { "備份含有重複或目錄項目" }
                    require(entry.size<=MAX_BYTES) { "備份過大" }
                    File(dir,entry.name).outputStream().use { output ->
                        val buffer=ByteArray(8192)
                        var entryBytes=0L
                        while(true) {
                            val count=zip.read(buffer); if(count<0) break
                            total+=count; entryBytes+=count
                            require(total<=MAX_BYTES && (entry.name!="manifest.json" || entryBytes<=65536)) { "備份解壓大小超過安全上限" }
                            output.write(buffer,0,count)
                        }
                    }
                    zip.closeEntry() // validates CRC as well as SHA-256 below
                }
            }
            require(seen==payloadNames+"manifest.json") { "備份檔案不完整" }
            val manifest=JsonCodec.json.decodeFromString<Manifest>(File(dir,"manifest.json").readText())
            require(manifest.schemaVersion in 1..4) { "不支援的備份版本" }
            require(manifest.createdAt>0 && manifest.appVersion.isNotBlank()) { "備份資訊不合法" }
            require(manifest.checksum.keys==payloadNames) { "缺少完整性檢查資料" }
            manifest.checksum.forEach { (name,sha) -> require(digest(File(dir,name))==sha) { "備份校驗失敗：$name" } }
            val sources=readLines<Source>(dir,"sources.jsonl").toList()
            require(sources.size==manifest.sourceCount && sources.map { it.sourceId }.distinct().size==sources.size)
            sources.forEach {
                require(it.sourceId.isNotBlank() && it.sourceId.length<=512 && it.name.isNotBlank() && it.lastSync>=0)
                require(it.access in listOf("public","google","file")) { "不支援的來源存取模式" }
                if(it.url.isNotEmpty()) require(it.url==SheetUrl.normalize(it.url)) { "來源網址必須為不含憑證的標準 CSV 網址" }
                if(it.access=="google") tw.language.listener.authoring.SheetLink.parse(it.url)
            }
            var count=0
            val itemKeys=HashSet<Pair<String,String>>()
            readLines<Item>(dir,"items.jsonl").forEach {
                validateKey(it.sourceId,it.itemId)
                require(itemKeys.add(it.sourceId to it.itemId)) { "教材主鍵重複" }
                require(listOf(it.zh,it.en,it.id).any(String::isNotBlank))
                count++
            }
            require(count==manifest.itemCount && (manifest.full || count==0))
            count=0
            val progressKeys=HashSet<Pair<String,String>>()
            readLines<Progress>(dir,"progress.jsonl").forEach {
                validateKey(it.sourceId,it.itemId)
                require(progressKeys.add(it.sourceId to it.itemId)) { "進度主鍵重複" }
                require(it.playCount>=0 && it.lastPlayedAt>=0 && it.skipUntil>=0 && it.modifiedAt>=0 && it.reviewPriority in 0..100)
                count++
            }
            require(count==manifest.progressCount)
            val queue=readLines<QueueEntry>(dir,"queue.jsonl").toList()
            require(queue.map { it.position }.distinct().size==queue.size && queue.all { it.position>=0 })
            require(queue.map { it.sourceId to it.itemId }.distinct().size==queue.size)
            queue.forEach { validateKey(it.sourceId,it.itemId) }
            if(manifest.full) require(queue.all { (it.sourceId to it.itemId) in itemKeys }) { "完整備份缺少佇列教材" }
            val session=readLines<Session>(dir,"session.jsonl").toList()
            require(session.size<=1)
            session.forEach {
                require(it.singleton==1 && it.position>=0 && (if(queue.isEmpty()) it.position==0 else it.position<queue.size) && it.offsetMs>=0 && it.sessionId.isNotBlank())
                it.options.validate()
            }
            val settings=readLines<Settings>(dir,"settings.jsonl").toList()
            require(settings.size==1 && settings[0].singleton==1 && settings[0].modifiedAt>=0)
            settings[0].playback.validate()
            require(settings[0].uiLanguage in setOf("zh-TW","en","id")) { "不支援的介面語言" }
            settings[0].aiProfile?.validate()
            val days=readLines<StudyDay>(dir,"days.jsonl").toList()
            require(days.size==manifest.dayCount && days.map { it.epochDay }.distinct().size==days.size)
            require(days.all { it.epochDay in -365000L..365000L })
            return BackupPreview(dir,manifest,sources)
        } catch(e: Exception) { dir.deleteRecursively(); throw e }
    }
    private fun validateKey(source: String, id: String) {
        require(source.isNotBlank() && source.length<=512 && id.isNotBlank() && id.length<=512)
    }
    inline fun <reified T> readLines(dir: File, name: String): Sequence<T> = sequence {
        File(dir,name).bufferedReader().use { reader ->
            while(true) {
                val line=boundedLine(reader) ?: break
                yield(JsonCodec.json.decodeFromString<T>(line))
            }
        }
    }
    fun boundedLine(reader: Reader): String? {
        val value=StringBuilder()
        while(true) {
            val char=reader.read()
            if(char<0) return if(value.isEmpty()) null else value.toString()
            if(char=='\n'.code) return value.toString()
            require(value.length<1_000_000) { "備份單筆資料過大" }
            value.append(char.toChar())
        }
    }
}

class BackupManager(private val repo: Repository, private val cache: File) {
    suspend fun export(full: Boolean, output: OutputStream) = withContext(Dispatchers.IO) {
        val dir=java.nio.file.Files.createTempDirectory(cache.toPath(),"backup-").toFile()
        try {
            val manifest=repo.mutation.withLock {
                repo.db.withTransaction {
                    val sources=repo.dao.sources()
                    write(dir,"sources.jsonl",sources)
                    var items=0; var progress=0
                    File(dir,"items.jsonl").bufferedWriter().use { writer ->
                        if(full) while(true) {
                            val page=repo.dao.itemPage(250,items); if(page.isEmpty()) break
                            page.forEach { writer.appendLine(JsonCodec.json.encodeToString(it)) }; items+=page.size
                        }
                    }
                    File(dir,"progress.jsonl").bufferedWriter().use { writer ->
                        while(true) {
                            val page=repo.dao.progressPage(250,progress); if(page.isEmpty()) break
                            page.forEach { writer.appendLine(JsonCodec.json.encodeToString(it)) }; progress+=page.size
                        }
                    }
                    write(dir,"queue.jsonl",repo.dao.queue())
                    write(dir,"session.jsonl",listOfNotNull(repo.dao.session()))
                    write(dir,"settings.jsonl",listOf(repo.dao.settings() ?: Settings()))
                    val days=repo.dao.days()
                    write(dir,"days.jsonl",days)
                    require(BackupArchive.payloadNames.sumOf { File(dir,it).length() }<=BackupArchive.MAX_BYTES) { "備份超過 128 MiB 安全上限" }
                    Manifest(createdAt=System.currentTimeMillis(),full=full,itemCount=items,progressCount=progress,
                        sourceCount=sources.size,checksum=BackupArchive.payloadNames.associateWith { BackupArchive.digest(File(dir,it)) },dayCount=days.size)
                }
            }
            File(dir,"manifest.json").writeText(JsonCodec.json.encodeToString(manifest))
            ZipOutputStream(BufferedOutputStream(output)).use { zip ->
                (BackupArchive.payloadNames+"manifest.json").sorted().forEach { name ->
                    zip.putNextEntry(ZipEntry(name)); File(dir,name).inputStream().use { it.copyTo(zip) }; zip.closeEntry()
                }
            }
        } finally { dir.deleteRecursively() }
    }
    suspend fun preview(input: InputStream): BackupPreview = withContext(Dispatchers.IO) { BackupArchive.read(input,cache) }
    suspend fun restore(preview: BackupPreview, replace: Boolean) = withContext(Dispatchers.IO) {
        // Re-read checksums immediately before changing the database.
        preview.manifest.checksum.forEach { (name,sha) -> require(BackupArchive.digest(File(preview.directory,name))==sha) }
        repo.mutation.withLock {
            repo.db.withTransaction {
                val dao=repo.dao
                if(replace) {
                    dao.clearQueue(); dao.clearSession(); dao.clearProgress(); dao.clearItems(); dao.clearSources(); dao.clearSettings(); dao.clearDocuments(); dao.clearDays(); dao.clearDraft()
                }
                val existing=dao.sources().associateBy { it.sourceId }
                dao.putSources(preview.sources.filter { replace || it.sourceId !in existing })
                BackupArchive.readLines<Item>(preview.directory,"items.jsonl").chunked(250).forEach { batch ->
                    // Local downloaded text wins on merge; progress is merged separately by timestamp.
                    dao.putItems(if(replace) batch else batch.filter { dao.item(it.sourceId,it.itemId)==null })
                }
                BackupArchive.readLines<Progress>(preview.directory,"progress.jsonl").chunked(250).forEach { batch ->
                    dao.putProgress(if(replace) batch else batch.filter { it.modifiedAt>(dao.progress(it.sourceId,it.itemId)?.modifiedAt ?: -1) })
                }
                val importedSettings=BackupArchive.readLines<Settings>(preview.directory,"settings.jsonl").single()
                if(replace || importedSettings.modifiedAt>(dao.settings()?.modifiedAt ?: -1)) dao.putSettings(importedSettings)
                dao.putDays(BackupArchive.readLines<StudyDay>(preview.directory,"days.jsonl").toList())
                if(replace || dao.queueCount()==0) {
                    dao.clearQueue()
                    dao.putQueue(BackupArchive.readLines<QueueEntry>(preview.directory,"queue.jsonl").toList())
                    BackupArchive.readLines<Session>(preview.directory,"session.jsonl").singleOrNull()?.let {
                        dao.putSession(it.copy(sessionId=java.util.UUID.randomUUID().toString()))
                    }
                }
            }
            repo.syncEpoch.incrementAndGet()
        }
    }
    private inline fun <reified T> write(dir: File, name: String, values: List<T>) {
        File(dir,name).bufferedWriter().use { writer -> values.forEach { writer.appendLine(JsonCodec.json.encodeToString(it)) } }
    }
}
