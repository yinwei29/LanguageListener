package tw.language.listener.documents

import android.content.*
import android.net.Uri
import android.provider.OpenableColumns
import androidx.room.withTransaction
import kotlinx.coroutines.sync.withLock
import tw.language.listener.data.*
import java.util.UUID

data class FilePlan(val source: Source,val binding: LocalDocument,val table: DocumentTable,val original: ByteArray,
    val missing: List<String>,val generatedIds: Int,val appDigest: String,val merged: List<Item>)

class DocumentStore(private val context: Context,private val repo: Repository) {
    private val resolver get()=context.contentResolver
    fun bytes(uri: String)=checkNotNull(resolver.openInputStream(Uri.parse(uri))) { "無法讀取檔案，請重新選擇" }.use(DocumentCodec::read)
    fun persist(uri: Uri) {
        resolver.takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try { resolver.takePersistableUriPermission(uri,Intent.FLAG_GRANT_WRITE_URI_PERMISSION) } catch(_: SecurityException) { /* Read-only imports remain usable. */ }
    }
    fun name(uri: Uri)=resolver.query(uri,arrayOf(OpenableColumns.DISPLAY_NAME),null,null,null)?.use { c -> if(c.moveToFirst()) c.getString(0) else null } ?: "lessons.csv"
    suspend fun cached(source: String): List<Item> {
        val result=mutableListOf<Item>();var after: String?=null
        while(true) { val page=repo.dao.sourcePage(source,after,250);if(page.isEmpty()) break;result+=page.filter { !it.deleted || it.status.lowercase() in listOf("deleted","removed") };after=page.last().itemId }
        return result
    }
    suspend fun prepare(uri: Uri,sourceId: String?=null,sheet: String=""): FilePlan {
        persist(uri)
        val title=name(uri);require(!title.endsWith(".xls",true) && !title.endsWith(".xlsm",true)) { "舊版 .xls／巨集檔請先轉存 .xlsx 或 UTF-8 CSV" }
        val raw=bytes(uri.toString());val table=DocumentCodec.decode(raw,sheet);val missing=table.missing;val generated=table.initialize()
        val selector=if(table.sheet.isEmpty()) "" else "name:"+table.sheets.entries.first { it.value==table.sheet }.key
        val old=repo.dao.documents().firstOrNull { it.uri==uri.toString() && it.sheet==selector }
        val id=sourceId ?: old?.sourceId ?: UUID.nameUUIDFromBytes((uri.toString()+"#"+table.sheet).toByteArray()).toString()
        require(old==null || old.sourceId==id) { "這個工作表已連接另一個來源" }
        val existing=repo.dao.sources().find { it.sourceId==id }
        require(existing==null || existing.sourceId!in listOf("demo",Repository.LOCAL_SOURCE_ID)) { "請選擇本機檔案來源重新連接" }
        val binding=repo.dao.documents().find { it.sourceId==id } ?: LocalDocument(id,uri.toString(),selector,DocumentCodec.hash(raw))
        val app=cached(id);val merged=DocumentMerge.merge(DocumentMerge.decode(binding.fileBase),if(existing==null) emptyList() else app,table.items(id))
        return FilePlan(existing?.copy(access="file") ?: Source(id,title,"",access="file"),binding.copy(uri=uri.toString(),sheet=selector,fileHash=DocumentCodec.hash(raw)),table,raw,missing,generated,DocumentMerge.digest(app),merged)
    }
    private fun requireWritable(uri: String) {
        check(resolver.persistedUriPermissions.any { it.uri.toString()==uri && it.isWritePermission }) { "檔案無法寫入，請改選可寫入位置" }
    }
    private fun writeChecked(binding: LocalDocument,expected: String,output: ByteArray,original: ByteArray) {
        require(output.size<=DocumentCodec.MAX_BYTES) { "檔案超過 32 MiB 安全上限" }
        requireWritable(binding.uri)
        check(DocumentCodec.hash(bytes(binding.uri))==expected) { "檔案已被其他程式修改，請重新預覽，未覆寫" }
        // Retain a device-only recovery copy before a provider truncates a stream.
        val recovery=java.io.File(context.noBackupFilesDir,"document-recovery").apply { mkdirs() }
        java.io.File(recovery,binding.sourceId+".bak").writeBytes(original)
        checkNotNull(resolver.openOutputStream(Uri.parse(binding.uri),"rwt")) { "檔案無法寫入，請改選可寫入位置" }.use { it.write(output) }
        check(DocumentCodec.hash(bytes(binding.uri))==DocumentCodec.hash(output)) { "檔案寫入未確認，本機教材與復原副本保留；請另存 CSV 後重新連接" }
    }
    suspend fun initialize(plan: FilePlan) = repo.mutation.withLock {
        check(DocumentMerge.digest(cached(plan.source.sourceId))==plan.appDigest) { "App 教材已改變，請重新預覽" }
        plan.table.update(plan.merged)
        val output=plan.table.encode()
        val needsWrite=plan.missing.isNotEmpty() || plan.generatedIds>0 || DocumentMerge.digest(plan.table.items(plan.source.sourceId))!=DocumentMerge.digest(DocumentCodec.decode(plan.original,plan.binding.sheet).let { old -> old.initialize();old.items(plan.source.sourceId) })
        if(needsWrite) writeChecked(plan.binding,plan.binding.fileHash,output,plan.original)
        else check(DocumentCodec.hash(bytes(plan.binding.uri))==plan.binding.fileHash) { "檔案已改變，請重新預覽" }
        repo.db.withTransaction {
            repo.dao.putSources(listOf(plan.source.copy(lastSync=System.currentTimeMillis(),error=null)))
            repo.dao.putItems(plan.merged)
            repo.dao.putDocument(plan.binding.copy(fileHash=if(needsWrite) DocumentCodec.hash(output) else plan.binding.fileHash,fileBase=DocumentMerge.encode(plan.merged)))
            repo.syncEpoch.incrementAndGet()
        }
    }
    suspend fun prepareSync(source: Source): FilePlan {
        val binding=checkNotNull(repo.dao.documents().find { it.sourceId==source.sourceId }) { "請先重新選擇本機檔案；備份不移轉檔案存取權限" }
        requireWritable(binding.uri)
        val raw=bytes(binding.uri);val table=DocumentCodec.decode(raw,binding.sheet)
        check(table.missing.isEmpty() && table.initialize()==0) { "檔案欄位或 ID 已改變，請重新連接並初始化" }
        val app=cached(source.sourceId)
        val merged=DocumentMerge.merge(DocumentMerge.decode(binding.fileBase),app,table.items(source.sourceId))
        return FilePlan(source,binding.copy(fileHash=DocumentCodec.hash(raw)),table,raw,emptyList(),0,DocumentMerge.digest(app),merged)
    }
    suspend fun commit(plan: FilePlan,items: List<Item> = plan.merged,cloudBase: String=plan.binding.cloudBase) = repo.mutation.withLock {
        check(repo.dao.sources().any { it.sourceId==plan.source.sourceId && it.access=="file" } && repo.dao.documents().any { it.sourceId==plan.source.sourceId && it.uri==plan.binding.uri && it.sheet==plan.binding.sheet }) { "請先重新選擇本機檔案；備份不移轉檔案存取權限" }
        check(DocumentMerge.digest(cached(plan.source.sourceId))==plan.appDigest) { "App 教材已改變，請重新預覽" }
        plan.table.update(items);val output=plan.table.encode()
        writeChecked(plan.binding,plan.binding.fileHash,output,plan.original)
        repo.db.withTransaction {
            repo.dao.putItems(items)
            repo.dao.putSources(listOf(plan.source.copy(lastSync=System.currentTimeMillis(),error=null)))
            repo.dao.putDocument(plan.binding.copy(fileHash=DocumentCodec.hash(output),fileBase=DocumentMerge.encode(items),cloudBase=cloudBase))
            repo.syncEpoch.incrementAndGet()
        }
    }
}
