package tw.language.listener.documents

import android.app.Application
import android.net.Uri
import androidx.lifecycle.*
import androidx.room.withTransaction
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.withLock
import tw.language.listener.*
import tw.language.listener.authoring.*
import tw.language.listener.data.*

data class CloudFilePlan(val file: FilePlan,val sheet: SheetPlan,val snapshot: CloudSnapshot?,val merged: List<Item>)
class DocumentViewModel(application: Application): AndroidViewModel(application) {
    private val app=application as ListenerApp
    private val repo=app.repository
    private val store=DocumentStore(app,repo)
    val dictionary=MutableStateFlow<tw.language.listener.dictionary.DictionaryFile?>(null)
    fun loadDictionary(sourceId: String)=task { dictionary.value=app.dictionaries.existing(sourceId) }
    fun buildDictionary(sourceId: String)=task { dictionary.value=app.dictionaries.refresh(sourceId);notice.value="CSV 字典檔已更新，可離線使用或另存到手機" }
    val documents=repo.dao.observeDocuments().stateIn(viewModelScope,SharingStarted.Eagerly,emptyList())
    val sources=repo.dao.observeSources().stateIn(viewModelScope,SharingStarted.Eagerly,emptyList())
    val busy=MutableStateFlow(false)
    val notice=MutableStateFlow<String?>(null)
    val plan=MutableStateFlow<FilePlan?>(null)
    val cloudInitialization=MutableStateFlow<Pair<Source,SheetPlan>?>(null)
    val cloudPlan=MutableStateFlow<CloudFilePlan?>(null)
    private suspend fun checkCloudNetwork() {
        if((repo.dao.settings() ?: Settings()).wifiOnly) {
            val cm=app.getSystemService(android.net.ConnectivityManager::class.java)
            check(cm.getNetworkCapabilities(cm.activeNetwork)?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)==true && !cm.isActiveNetworkMetered) { "目前網路不符合同步政策，保留原有教材" }
        }
    }
    private fun task(block: suspend ()->Unit) {
        if(busy.value) return
        viewModelScope.launch { perform(block) }
    }
    private suspend fun perform(block: suspend ()->Unit) {
        busy.value=true
        try { withContext(Dispatchers.IO) { block() } }
        catch(e: CancellationException) { throw e }
        catch(e: Exception) { notice.value=e.message ?: "檔案操作失敗，App 教材保留" }
        finally { busy.value=false }
    }
    fun inspect(uri: Uri,sourceId: String?=null,sheet: String="")=task { plan.value=store.prepare(uri,sourceId,sheet) }
    fun create(uri: Uri,sourceId: String?=null)=task {
        store.persist(uri)
        check(store.bytes(uri.toString()).isEmpty()) { "選取的位置已有資料，請使用選擇檔案而非建立新檔" }
        val table=DocumentCodec.decode(byteArrayOf());table.initialize();table.headers+=GoogleSheets.EXTRA_HEADERS
        checkNotNull(app.contentResolver.openOutputStream(uri,"rwt")).use { it.write(table.encode()) }
        plan.value=store.prepare(uri,sourceId)
    }
    fun initialize(value: FilePlan)=task { store.initialize(value);plan.value=null;notice.value="本機檔案已連接，可新增教材與離線播放" }
    fun syncFile(source: Source)=task {
        val value=store.prepareSync(source);store.commit(value)
        notice.value="App 與本機檔案已同步，學習進度保留"
    }
    fun export(uri: Uri,sourceId: String)=task {
        val snapshot=app.dictionaries.refresh(sourceId)
        checkNotNull(app.contentResolver.openOutputStream(uri,"rwt")).use { output -> snapshot.file.inputStream().use { it.copyTo(output) } }
        check(DocumentCodec.hash(store.bytes(uri.toString()))==snapshot.sha256) { "字典檔寫入未確認，App 教材保留，請重新另存" }
        dictionary.value=snapshot
        notice.value="CSV 已匯出，可用 Excel 開啟"
    }
    fun exportAndConnect(uri: Uri,sourceId: String)=task {
        val original=checkNotNull(repo.dao.sources().find { it.sourceId==sourceId }) { "來源已移除" }
        store.persist(uri)
        check(store.bytes(uri.toString()).isEmpty()) { "選取的位置已有資料，請使用選擇檔案而非建立新檔" }
        val snapshot=app.dictionaries.refresh(sourceId)
        checkNotNull(app.contentResolver.openOutputStream(uri,"rwt")).use { output -> snapshot.file.inputStream().use { it.copyTo(output) } }
        check(DocumentCodec.hash(store.bytes(uri.toString()))==snapshot.sha256) { "字典檔寫入未確認，App 教材保留，請重新另存" }
        dictionary.value=snapshot
        check(repo.dao.sources().any { it==original }) { "來源或備份狀態已改變，尚未寫入，請重新審核" }
        store.initialize(store.prepare(uri,sourceId))
        notice.value="CSV 已建立並連接此來源，可離線同步檔案或連接 Google Sheets"
    }
    fun recover(uri: Uri,sourceId: String)=task {
        val backup=java.io.File(app.noBackupFilesDir,"document-recovery/$sourceId.bak")
        require(backup.isFile) { "尚無檔案復原副本" }
        checkNotNull(app.contentResolver.openOutputStream(uri,"rwt")).use { out -> backup.inputStream().use { it.copyTo(out) } }
        notice.value="同步前的檔案副本已另存"
    }
    fun previewCloud(source: Source,url: String,auth: AuthoringViewModel) {
        if(busy.value) return
        auth.useGoogle { token -> perform {
            checkCloudNetwork()
            prepareCloud(source,url,token)
        } }
    }
    private suspend fun prepareCloud(source: Source,url: String,token: String) {
        cloudPlan.value=null
        require(url.isNotBlank()) { "請輸入要同步的 Google Sheets 網址" }
        val local=store.prepareSync(source)
        val required=GoogleSheets.HEADERS+GoogleSheets.EXTRA_HEADERS.filter { h -> local.merged.any { it.text(h).isNotBlank() } }
        val inspected=app.sheets.inspect(source.name,url,token,required)
        val linked=source.copy(url=inspected.url,access="file")
        check(repo.dao.sources().none { it.sourceId!=source.sourceId && it.url==linked.url }) { "此 Sheets 已連接其他來源，請先選擇該既有來源再連接本機檔案" }
        if(inspected.missing.isNotEmpty() && inspected.headers.isNotEmpty()) { cloudInitialization.value=source to inspected;return }
        val file=local.let { f -> f.copy(source=linked,binding=if(source.url==linked.url) f.binding else f.binding.copy(cloudBase="[]")) }
        val snapshot=if(inspected.headers.isEmpty()) null else app.sheets.snapshot(linked,token)
        val merged=DocumentMerge.merge(DocumentMerge.decode(file.binding.cloudBase),file.merged,snapshot?.items ?: emptyList())
        cloudPlan.value=CloudFilePlan(file,inspected,snapshot,merged)
    }
    fun initializeCloud(value: Pair<Source,SheetPlan>,auth: AuthoringViewModel) {
        auth.useGoogle { token -> perform {
            checkCloudNetwork()
            app.sheets.initialize(value.second,token)
            cloudInitialization.value=null
            prepareCloud(value.first,value.second.url,token)
        } }
    }
    fun commitCloud(value: CloudFilePlan,auth: AuthoringViewModel) {
        if(busy.value) return
        auth.useGoogle { token -> perform {
            checkCloudNetwork()
            check(DocumentMerge.digest(store.cached(value.file.source.sourceId))==value.file.appDigest) { "App 教材已改變，請重新預覽" }
            check(DocumentCodec.hash(store.bytes(value.file.binding.uri))==value.file.binding.fileHash) { "檔案已被其他程式修改，請重新預覽，未覆寫" }
            val expected=value.snapshot ?: run {
                app.sheets.initialize(value.sheet,token)
                app.sheets.snapshot(value.file.source,token).also { check(it.items.isEmpty()) { "初始化後雲端內容已改變，請重新預覽" } }
            }
            app.sheets.writeMerged(value.file.source,expected,value.merged,token)
            store.commit(value.file,value.merged,DocumentMerge.encode(value.merged))
            cloudPlan.value=null;notice.value="本機檔案、App 與 Google Sheets 已同步"
        } }
    }
}
