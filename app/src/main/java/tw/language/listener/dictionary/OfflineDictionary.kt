package tw.language.listener.dictionary

import android.util.AtomicFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import tw.language.listener.authoring.*
import tw.language.listener.data.*
import tw.language.listener.documents.DocumentCodec
import tw.language.listener.sync.CsvReader
import java.io.*
import java.security.MessageDigest

/** A derived, durable CSV dictionary. Room remains authoritative; no network or credentials. */
data class DictionaryFile(val sourceId: String,val file: File,val entries: Int,val bytes: Long,val sha256: String)
class OfflineDictionary(private val repo: Repository,private val directory: File,private val maxBytes: Int=DocumentCodec.MAX_BYTES) {
    init { require(maxBytes in 1..DocumentCodec.MAX_BYTES) }
    private fun path(sourceId: String): File {
        require(sourceId.isNotBlank() && sourceId.length<=512)
        val key=MessageDigest.getInstance("SHA-256").digest(sourceId.toByteArray()).joinToString("") { "%02x".format(it) }
        return File(directory,"LanguageListener-$key.csv")
    }
    suspend fun refresh(sourceId: String): DictionaryFile=withContext(Dispatchers.IO) { repo.mutation.withLock {
        check(repo.dao.sources().any { it.sourceId==sourceId }) { "來源已移除" }
        check(directory.isDirectory || directory.mkdirs()) { "無法建立本機字典目錄" }
        val target=path(sourceId);val atomic=AtomicFile(target);val output=atomic.startWrite()
        try {
            val writer=OutputStreamWriter(output,Charsets.UTF_8).buffered()
            writer.write("\uFEFF"+GoogleSheets.csv(GoogleSheets.ALL_HEADERS)+"\r\n")
            var after: String?=null;var count=0
            while(true) {
                currentCoroutineContext().ensureActive()
                val page=repo.dao.sourcePage(sourceId,after,250);if(page.isEmpty()) break
                for(item in page) {
                    writer.write(GoogleSheets.csv(GoogleSheets.ALL_HEADERS.map { GoogleSheets.cell(item,it) })+"\r\n")
                    count++
                    require(output.channel.size()<=maxBytes) { "字典檔超過 32 MiB，請拆成不同來源" }
                }
                writer.flush()
                require(output.channel.size()<=maxBytes) { "字典檔超過 32 MiB，請拆成不同來源" }
                after=page.last().itemId
            }
            writer.flush();atomic.finishWrite(output)
            describeFile(sourceId,target,count)
        } catch(e: Throwable) { atomic.failWrite(output);throw e }
    } }
    /** Used only for display. Export always regenerates from current reviewed Room data. */
    suspend fun existing(sourceId: String): DictionaryFile?=withContext(Dispatchers.IO) { repo.mutation.withLock {
        if(repo.dao.sources().none { it.sourceId==sourceId }) return@withLock null
        val atomic=AtomicFile(path(sourceId))
        if(!atomic.baseFile.exists() && !File(atomic.baseFile.path+".bak").exists()) return@withLock null
        val count=atomic.openRead().bufferedReader(Charsets.UTF_8).use { input ->
            input.mark(1);if(input.read()!=0xFEFF) input.reset()
            val csv=CsvReader(input);check(csv.row()==GoogleSheets.ALL_HEADERS)
            var n=0;while(csv.row()!=null) { currentCoroutineContext().ensureActive();n++ };n
        }
        describeFile(sourceId,atomic.baseFile,count)
    } }
    private fun describeFile(sourceId: String,file: File,count: Int): DictionaryFile {
        val digest=MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input -> val buffer=ByteArray(8192);while(true) { val n=input.read(buffer);if(n<0) break;digest.update(buffer,0,n) } }
        return DictionaryFile(sourceId,file,count,file.length(),digest.digest().joinToString("") { "%02x".format(it) })
    }
    suspend fun publishReviewed(draft: AiDraft,selected: List<Item>): DictionaryFile {
        AiSourceDrafts(repo).publish(draft,selected)
        try { return refresh(draft.request.sourceId) }
        catch(e: kotlinx.coroutines.CancellationException) { throw e }
        catch(e: Exception) { throw IllegalStateException("教材已儲存，但字典檔尚未完成，請到 CSV 字典檔重新建立",e) }
    }
    suspend fun clear()=withContext(Dispatchers.IO) { repo.mutation.withLock {
        directory.listFiles().orEmpty().filter { it.name.startsWith("LanguageListener-") }.forEach { check(it.delete() || !it.exists()) }
    } }
}
