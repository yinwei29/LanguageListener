package tw.language.listener.authoring

import android.app.Application
import android.app.PendingIntent
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.withTransaction
import com.google.android.gms.common.api.ApiException
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import tw.language.listener.ListenerApp
import tw.language.listener.data.*
import tw.language.listener.sync.SheetImporter
import tw.language.listener.sync.SyncWorker
import java.io.File
import java.io.IOException

class AuthoringViewModel(application: Application) : AndroidViewModel(application) {
    private val app=application as ListenerApp
    private val repo=app.repository
    private val preferences=AiPreferences(app.vault)
    val busy=MutableStateFlow(false)
    val notice=MutableStateFlow<String?>(null)
    val resolution=MutableStateFlow<PendingIntent?>(null)
    val plan=MutableStateFlow<SheetPlan?>(null)
    val profile=MutableStateFlow(AiProfile())
    val hasKey=MutableStateFlow(false)
    val keySaved=MutableStateFlow(0)
    val draft=repo.dao.observeDraft().map { record -> record?.let { JsonCodec.json.decodeFromString<AiDraft>(it.payload) } }
        .stateIn(viewModelScope,SharingStarted.Eagerly,null)
    private var operation: Job?=null
    private var pendingAction: (suspend (String)->Unit)?=null
    private var pendingWrite=true
    init {
        viewModelScope.launch {
            repo.dao.observeSettings().map { it?.aiProfile ?: AiProfile() }.distinctUntilChanged().collect { value ->
                profile.value=value
                try { hasKey.value=withContext(Dispatchers.IO) { preferences.hasKey(value) } }
                catch(_: Exception) { hasKey.value=false; notice.value="本機金鑰無法讀取，請重新設定" }
            }
        }
    }
    private fun task(block: suspend ()->Unit) {
        if(busy.value) return
        operation=viewModelScope.launch {
            busy.value=true
            try { withContext(Dispatchers.IO) { block() } }
            catch(e: CancellationException) { throw e }
            catch(e: Exception) {
                notice.value=when(e) {
                    is ApiException -> "Google 授權失敗（${e.statusCode}），請確認 Google Play 服務、Cloud OAuth 的套件名稱／簽章指紋及測試帳號設定"
                    is SerializationException -> "服務回應或草稿格式不合法，請調整模型或重新產生"
                    is IOException -> "網路連線失敗。若正在寫回，結果可能未確認；保留草稿原樣重試可用穩定 ID 去重"
                    is RemoteError -> {
                        if(e.status==401 && e.message.orEmpty().startsWith("Google Sheets")) app.google.clear()
                        e.message
                    }
                    else -> e.message ?: "操作失敗，原有離線教材仍可使用"
                }
            } finally { busy.value=false }
        }
    }
    fun cancel() { operation?.cancel(); pendingAction=null; resolution.value=null; notice.value="已取消；若已送出寫回請保留草稿原樣重試確認" }
    private suspend fun withGoogle(write: Boolean=true, action: suspend (String)->Unit) {
        val result=app.google.authorize(write)
        if(result.hasResolution()) {
            pendingAction=action
            pendingWrite=write
            resolution.value=checkNotNull(result.pendingIntent) { "Google 未提供授權畫面" }
        } else { app.google.accept(result,write); action(checkNotNull(result.accessToken)) }
    }
    fun launchedResolution() { resolution.value=null }
    fun authorizationResult(intent: Intent?) = task {
        val action=pendingAction; pendingAction=null
        checkNotNull(intent) { "Google 授權已取消，尚未初始化或寫入" }
        val result=app.google.fromIntent(intent)
        app.google.accept(result,if(action!=null) pendingWrite else false)
        if(action!=null) action(checkNotNull(result.accessToken)) else notice.value="授權完成，請重新選擇初始化或寫入操作"
    }
    fun inspect(name: String, url: String)=task { withGoogle { token -> plan.value=app.sheets.inspect(name,url,token) } }
    fun readOnly(name: String, url: String)=task {
        withGoogle(false) { token ->
            val value=app.sheets.inspect(name,url,token)
            tw.language.listener.sync.SheetColumns(value.headers)
            saveSource(value.source())
            notice.value="已以授權方式加入來源並排程下載；未初始化或修改工作表"
        }
    }
    private suspend fun saveSource(remote: Source) {
        repo.mutation.withLock {
            repo.db.withTransaction {
                val previous=repo.dao.sources().find { it.sourceId==remote.sourceId }
                repo.dao.putSources(listOf(previous?.copy(name=remote.name,access="google",error=null) ?: remote))
                repo.syncEpoch.incrementAndGet()
            }
        }
        SyncWorker.enqueue(app,(repo.dao.settings() ?: Settings()).wifiOnly,replace=true)
    }
    fun dismissPlan() { if(!busy.value) plan.value=null }
    fun initialize(value: SheetPlan)=task {
        withGoogle { token ->
            val remote=app.sheets.initialize(value,token)
            saveSource(remote)
            plan.value=null
            notice.value="教材欄位已就緒並儲存來源，已排程下載；可以開始產生 AI 教材"
        }
    }
    fun clearGoogle()=task { app.google.clear(); notice.value="本機 Google 同步已停用；未撤銷 Google 帳號既有授權，離線教材保留" }
    fun saveProfile(value: AiProfile, key: String)=task {
        preferences.save(value,key)
        repo.mutation.withLock {
            val previous=repo.dao.settings() ?: Settings()
            repo.dao.putSettings(previous.copy(aiProfile=value,modifiedAt=System.currentTimeMillis()))
        }
        profile.value=value; hasKey.value=preferences.hasKey(value); keySaved.value++
        notice.value="AI 設定已儲存於本機；不同供應商與網址不會共用金鑰"
    }
    fun clearKey()=task { preferences.clear(profile.value); hasKey.value=false; notice.value="此供應商的本機 API 金鑰已移除" }
    fun generate(request: AiRequest)=task {
        request.validate()
        check(repo.dao.sources().any { it.sourceId==request.sourceId && it.access=="google" }) { "請先連接並初始化可寫入的 Google Sheets 來源" }
        val generated=AiGenerator().generate(profile.value,preferences.key(profile.value),request)
        val desired=generated.items.map(AuthoringValidation::fingerprint).toSet()
        val existing=mutableSetOf<String>()
        var after: String?=null
        while(true) {
            val page=repo.dao.sourcePage(request.sourceId,after,250)
            if(page.isEmpty()) break
            for(item in page) if(item.enabled && !item.deleted) {
                val fingerprint=AuthoringValidation.fingerprint(item)
                if(fingerprint in desired) existing+=fingerprint
            }
            after=page.last().itemId
        }
        val items=generated.items.filter { AuthoringValidation.fingerprint(it) !in existing }
        check(items.isNotEmpty()) { "產生的教材皆與離線快取重複，請更換主題" }
        repo.mutation.withLock {
            check(repo.dao.sources().any { it.sourceId==request.sourceId && it.access=="google" }) { "來源已移除，未保存草稿" }
            saveDraft(generated.copy(items=items,removedDuplicates=generated.removedDuplicates+generated.items.size-items.size))
        }
        notice.value="已產生 ${items.size}/${request.count} 筆待審核教材，尚未寫入 Sheets，也未宣稱已搜尋或查證"
    }
    private suspend fun saveDraft(value: AiDraft) { repo.dao.putDraft(DraftRecord(payload=JsonCodec.json.encodeToString(value))) }
    fun writeReviewed(value: AiDraft, selected: List<Item>)=task {
        AuthoringValidation.validateReview(value,selected)
        val saved=value.copy(items=value.items.map { old -> selected.find { it.itemId==old.itemId } ?: old },selectedIds=selected.map { it.itemId })
        saveDraft(saved) // Stable IDs and edited text survive ambiguous network failures/process death.
        withGoogle { token ->
            val source=checkNotNull(repo.dao.sources().find { it.sourceId==value.request.sourceId && it.access=="google" }) { "來源已移除或改變，未寫入" }
            val epoch=repo.syncEpoch.get()
            val result=app.sheets.append(source,selected,token) {
                check(repo.syncEpoch.get()==epoch) { "來源或備份狀態已改變，尚未寫入，請重新審核" }
            }
            saveDraft(saved.copy(written=true,writtenCount=result.written,skippedCount=result.skipped))
            SyncWorker.enqueue(app,(repo.dao.settings() ?: Settings()).wifiOnly,replace=true)
            notice.value="已確認寫入 ${result.written} 筆，已存在略過 ${result.skipped} 筆；已依網路政策排程下載，完成後可離線學習"
        }
    }
}
