@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package tw.language.listener

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.Player
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import tw.language.listener.backup.*
import tw.language.listener.data.*
import tw.language.listener.playback.ListeningService
import tw.language.listener.sync.SyncWorker

private val android.content.Context.uiStore by preferencesDataStore("interface")

@UnstableApi
class ListenerViewModel(app: Application) : AndroidViewModel(app) {
    val repo=(app as ListenerApp).repository
    val backup=BackupManager(repo,app.cacheDir)
    val settings=repo.dao.observeSettings().map { it ?: Settings() }.stateIn(viewModelScope,SharingStarted.Eagerly,Settings())
    val stats=repo.dao.stats().stateIn(viewModelScope,SharingStarted.Eagerly,Stats(0,0,0))
    val sources=repo.dao.observeSources().stateIn(viewModelScope,SharingStarted.Eagerly,emptyList())
    val syncStatus=androidx.work.WorkManager.getInstance(app).getWorkInfosForUniqueWorkFlow(SyncWorker.WORK_NAME)
        .map { jobs -> jobs.maxByOrNull { job ->
            job.tags.mapNotNull { it.removePrefix("scheduled-at:").toLongOrNull() }.maxOrNull() ?: 0L
        }?.state }
        .stateIn(viewModelScope,SharingStarted.Eagerly,null)
    val categories=repo.dao.categories().stateIn(viewModelScope,SharingStarted.Eagerly,emptyList())
    val streak=repo.dao.playedDays().map { Repository.streakDays(it) }.stateIn(viewModelScope,SharingStarted.Eagerly,0)
    val session=repo.dao.observeSession().stateIn(viewModelScope,SharingStarted.Eagerly,null)
    val queueSize=repo.dao.observeQueueSize().stateIn(viewModelScope,SharingStarted.Eagerly,0)
    val message=MutableStateFlow<String?>(null)
    val busy=MutableStateFlow(false)
    val item=MutableStateFlow<Item?>(null)
    val progress=item.flatMapLatest { current ->
        if(current==null) flowOf(null) else repo.dao.observeProgress(current.sourceId,current.itemId)
    }.stateIn(viewModelScope,SharingStarted.Eagerly,null)
    val playing=MutableStateFlow(false)
    val buffering=MutableStateFlow(false)
    val index=MutableStateFlow(0)
    val position=MutableStateFlow(0L)
    val duration=MutableStateFlow(0L)
    val voiceStatus=MutableStateFlow<Map<String,Boolean>>(emptyMap())
    val preview=MutableStateFlow<BackupPreview?>(null)
    val library=MutableStateFlow<List<Item>>(emptyList())
    val hasMore=MutableStateFlow(false)
    val tab=app.uiStore.data.map { it[intPreferencesKey("tab")] ?: 0 }.stateIn(viewModelScope,SharingStarted.Eagerly,0)
    private var controller: MediaController?=null
    private val future=MediaController.Builder(app,SessionToken(app,ComponentName(app,ListeningService::class.java))).buildAsync()
    init {
        future.addListener({
            try {
                controller=future.get()
                controller?.addListener(object: Player.Listener {
                    override fun onEvents(player: Player, events: Player.Events) { updatePlayer() }
                    override fun onPlayerError(error: PlaybackException) {
                        var cause: Throwable=error
                        while(cause.cause!=null) cause=cause.cause!!
                        message.value=cause.message ?: "無法播放，請檢查離線語音包"
                    }
                })
                updatePlayer()
            } catch(e: Exception) { message.value="播放器連線失敗：${e.message}" }
        },ContextCompat.getMainExecutor(app))
        viewModelScope.launch {
            repo.seed()
            val config=repo.dao.settings() ?: Settings()
            if(config.syncOnLaunch) SyncWorker.enqueue(getApplication(),config.wifiOnly)
            session.collect { saved ->
                if(controller?.mediaItemCount==0) {
                    index.value=saved?.position ?: 0
                    refreshItem()
                }
            }
        }
        viewModelScope.launch {
            while(isActive) {
                controller?.let { position.value=it.currentPosition; duration.value=it.duration.coerceAtLeast(0) }
                delay(500)
            }
        }
    }
    private fun updatePlayer() {
        controller?.let {
            playing.value=it.playWhenReady && it.playbackState in listOf(Player.STATE_READY,Player.STATE_BUFFERING)
            buffering.value=it.playbackState==Player.STATE_BUFFERING
            index.value=it.currentMediaItemIndex.coerceAtLeast(0)
            viewModelScope.launch { refreshItem() }
        }
    }
    private suspend fun refreshItem() {
        val entry=repo.dao.queueAt(index.value)
        item.value=entry?.let { repo.dao.item(it.sourceId,it.itemId) }
    }
    fun chooseTab(value: Int) { viewModelScope.launch { getApplication<Application>().uiStore.edit { it[intPreferencesKey("tab")]=value } } }
    fun runTask(block: suspend ()->Unit) {
        if(busy.value) return
        viewModelScope.launch {
            busy.value=true
            try { block() } catch(e: CancellationException) { throw e }
            catch(e: Exception) { message.value=e.message ?: "操作失敗" }
            finally { busy.value=false }
        }
    }
    fun saveSettings(value: Settings) = runTask {
        val previous=repo.dao.settings()
        repo.saveSettings(value)
        if(previous?.wifiOnly!=value.wifiOnly) SyncWorker.enqueue(getApplication(),value.wifiOnly,replace=true)
    }
    fun start(options: PlaybackOptions=settings.value.playback) = runTask {
        controller?.pause(); controller?.stop()
        val count=repo.createSession(options)
        index.value=0; refreshItem()
        if(count==0) {
            reload(false)
            message.value="沒有符合條件的離線教材，請同步來源或調整篩選"
        }
        else {
            if(options.count>count) message.value="符合條件僅 $count 筆，將播放全部可用教材"
            reload(true); chooseTab(1)
        }
    }
    private fun reload(play: Boolean) {
        getApplication<Application>().startService(Intent(getApplication(),ListeningService::class.java)
            .setAction(ListeningService.ACTION_RELOAD).putExtra("play",play))
    }
    fun toggle() {
        val player=controller ?: return
        if(player.playWhenReady && player.playbackState in listOf(Player.STATE_READY,Player.STATE_BUFFERING)) player.pause()
        else {
            if(player.mediaItemCount==0) { start(); return }
            if(player.playbackState==Player.STATE_ENDED) player.seekToDefaultPosition(0)
            player.prepare(); player.play()
        }
    }
    fun next() { controller?.let { if(it.hasNextMediaItem()) it.seekToNextMediaItem() else { it.pause(); message.value="已到場次最後一筆" } } }
    fun previous() { controller?.let { if(it.hasPreviousMediaItem()) it.seekToPreviousMediaItem() else it.seekTo(0) } }
    fun seek(fraction: Float) { controller?.let { if(it.duration>0) it.seekTo((it.duration*fraction).toLong()) } }
    fun mark(action: String) = runTask {
        val current=item.value ?: return@runTask
        repo.updateProgress(current.sourceId,current.itemId) { old -> when(action) {
            "learned" -> old.copy(learned=true,skipUntil=0,reviewPriority=0)
            "reset" -> old.copy(learned=false,skipUntil=0,reviewPriority=0)
            "favorite" -> old.copy(favorite=!old.favorite)
            "review" -> old.copy(reviewPriority=if(old.reviewPriority>0) 0 else 1)
            "7", "30" -> old.copy(skipUntil=System.currentTimeMillis()+action.toLong()*86_400_000L)
            else -> old
        } }
        if(action in listOf("learned","7","30")) next()
    }
    fun sync() { SyncWorker.enqueue(getApplication(),settings.value.wifiOnly); message.value="已排入同步工作；離線時會等待網路" }
    fun addSource(name: String, url: String) = runTask {
        repo.addSource(name,url)
        SyncWorker.enqueue(getApplication(),(repo.dao.settings() ?: Settings()).wifiOnly,replace=true)
        message.value="已新增來源並重新排程同步"
    }
    fun removeSource(id: String) = runTask {
        repo.removeSource(id)
        SyncWorker.enqueue(getApplication(),(repo.dao.settings() ?: Settings()).wifiOnly,replace=true)
    }
    fun checkVoices() = runTask { voiceStatus.value=(getApplication<Application>() as ListenerApp).speech.checkVoices() }
    fun search(query: String, category: String, more: Boolean=false) = runTask {
        val offset=if(more) library.value.size else 0
        val page=repo.dao.search(query,category,100,offset)
        library.value=if(more) library.value+page else page
        hasMore.value=page.size==100
    }
    fun markLibrary(value: Item, learned: Boolean) = runTask {
        repo.updateProgress(value.sourceId,value.itemId) { it.copy(learned=learned,skipUntil=0) }
        message.value=if(learned) "已標記學會" else "已恢復未學狀態"
    }
    fun export(uri: android.net.Uri, full: Boolean) = runTask {
        val resolver=getApplication<Application>().contentResolver
        backup.export(full,checkNotNull(resolver.openOutputStream(uri,"wt")))
        message.value="ZIP 備份已匯出（不含憑證及 TTS 語音包）"
    }
    fun import(uri: android.net.Uri) = runTask {
        preview.value?.close()
        preview.value=getApplication<Application>().contentResolver.openInputStream(uri)!!.use { backup.preview(it) }
    }
    fun dismissPreview() { preview.value?.close(); preview.value=null }
    fun restore(replace: Boolean) = runTask {
        val pending=preview.value ?: return@runTask
        controller?.pause(); controller?.stop()
        backup.restore(pending,replace)
        dismissPreview(); reload(false); message.value="還原完成；輕量備份的教材需重新同步，TTS 語音包需重新安裝"
    }
    override fun onCleared() {
        MediaController.releaseFuture(future); preview.value?.close(); super.onCleared()
    }
}
