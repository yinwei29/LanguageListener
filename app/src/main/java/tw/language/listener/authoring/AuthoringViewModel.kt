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
    val models=MutableStateFlow<AiModelList?>(null)
    val aiError=MutableStateFlow<String?>(null)
    val generationProgress=MutableStateFlow<Pair<Int,Int>?>(null)
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
                        if(e.status==401 && e.service=="Google Sheets") app.google.clear()
                        e.message
                    }
                    else -> e.message ?: "操作失敗，原有離線教材仍可使用"
                }
            } finally { busy.value=false }
        }
    }
    fun cancel() { val generating=generationProgress.value!=null;operation?.cancel(); pendingAction=null; resolution.value=null; notice.value=if(generating) "已停止產生；已完成批次的草稿保留，可繼續剩餘批次" else "已取消；若已送出寫回請保留草稿原樣重試確認" }
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
    fun useGoogle(action: suspend (String)->Unit)=task { withGoogle(true,action) }
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
    fun loadModels(value: AiProfile,newKey: String)=task {
        aiError.value=null
        try {
            val key=newKey.trim().ifEmpty { preferences.key(value) }
            models.value=AiModelList(value.keySlot(),AiModels().load(value,key))
            notice.value="模型清單已載入，請選擇模型後儲存 AI 設定"
        } catch(e: CancellationException) { throw e } catch(e: Exception) { aiError.value=e.message;throw e }
    }
    fun clearKey()=task { preferences.clear(profile.value); hasKey.value=false; notice.value="此供應商的本機 API 金鑰已移除" }
    fun generate(request: AiRequest)=task { generateWithCheckpoints(request,null) }
    fun resumeGeneration()=task {
        val value=checkNotNull(draft.value) { "沒有可繼續的草稿" }
        generateWithCheckpoints(value.request,value)
    }
    private suspend fun generateWithCheckpoints(request: AiRequest,resume: AiDraft?) {
        aiError.value=null
        generationProgress.value=(resume?.generatedCount ?: 0) to request.count
        try {
            request.validate()
            val settings=profile.value
            check(repo.dao.sources().any { it.sourceId==request.sourceId && (it.access in listOf("google","file") || it.sourceId==Repository.LOCAL_SOURCE_ID) }) { "請先建立本機教材或連接檔案／Google Sheets 來源" }
            val result=AiBatchGenerator().generate(settings,preferences.key(settings),request,resume,checkpoint={ value ->
                repo.mutation.withLock {
                    check(repo.dao.sources().any { it.sourceId==request.sourceId && (it.access in listOf("google","file") || it.sourceId==Repository.LOCAL_SOURCE_ID) }) { "來源已移除，未保存草稿" }
                    saveDraft(value)
                }
                generationProgress.value=value.generatedCount to request.count
            },existing={ candidates ->
                val desired=candidates.map(AuthoringValidation::fingerprint).toSet();val found=mutableSetOf<String>();var after: String?=null
                while(true) {
                    val page=repo.dao.sourcePage(request.sourceId,after,250);if(page.isEmpty()) break
                    for(item in page) if(item.enabled && !item.deleted) AuthoringValidation.fingerprint(item).let { if(it in desired) found+=it }
                    after=page.last().itemId
                }
                found
            })
            notice.value=if(result.items.isEmpty()) "產生的教材皆與既有內容重複，請更換主題" else "分批產生完成；請審核實際新增筆數，尚未寫入檔案或雲端"
        } catch(e: CancellationException) { throw e }
        catch(e: Exception) { aiError.value=e.message ?: "AI 產生失敗，已完成的草稿保留";throw e }
        finally { generationProgress.value=null }
    }
    private suspend fun saveDraft(value: AiDraft) { repo.dao.putDraft(DraftRecord(payload=JsonCodec.json.encodeToString(value))) }
    fun writeReviewed(value: AiDraft, selected: List<Item>)=task {
        AuthoringValidation.validateReview(value,selected)
        val saved=value.copy(items=value.items.map { old -> selected.find { it.itemId==old.itemId } ?: old },selectedIds=selected.map { it.itemId })
        saveDraft(saved) // Stable IDs and edited text survive ambiguous network failures/process death.
        val destination=checkNotNull(repo.dao.sources().find { it.sourceId==value.request.sourceId }) { "來源已移除或改變，未寫入" }
        if(destination.access=="file" || destination.sourceId==Repository.LOCAL_SOURCE_ID) {
            repo.mutation.withLock { repo.db.withTransaction {
                check(repo.dao.sources().any { it==destination }) { "來源已移除或改變，未寫入" }
                selected.forEach { item ->
                    val old=repo.dao.item(item.sourceId,item.itemId)
                    check(old==null || old==item) { "教材 ID 與本機內容衝突，請重新審核" }
                }
                repo.dao.putItems(selected)
                saveDraft(saved.copy(written=true,writtenCount=selected.size,skippedCount=0))
                repo.syncEpoch.incrementAndGet()
            } }
            notice.value="已儲存審核教材到 App；檔案來源請再按同步本機檔案，或預覽與 Sheets 同步"
            return@task
        }
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
