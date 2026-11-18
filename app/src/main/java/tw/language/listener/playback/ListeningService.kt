package tw.language.listener.playback

import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import androidx.media3.common.*
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.*
import kotlinx.coroutines.*
import tw.language.listener.ListenerApp
import tw.language.listener.MainActivity
import tw.language.listener.data.*

@UnstableApi
class ListeningService : MediaSessionService() {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private val repo by lazy { (application as ListenerApp).repository }
    private var session: MediaSession?=null
    private var player: ExoPlayer?=null
    private var queue: List<QueueEntry> = emptyList()
    private var loadJob: Job?=null
    private var countedIndex=-1
    private var loadedSessionId=""
    override fun onCreate() {
        super.onCreate()
        buildPlayer(PlaybackOptions())
        loadJob=scope.launch { restoreQueue(false) }
        scope.launch {
            while(isActive) { delay(2000); persist() }
        }
    }
    private fun buildPlayer(options: PlaybackOptions) {
        val speech=(application as ListenerApp).speech
        val engine=ExoPlayer.Builder(this).setMediaSourceFactory(DefaultMediaSourceFactory(this)
            .setDataSourceFactory { SpeechDataSource(repo,speech,options) })
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_SPEECH).build(),true)
            .setHandleAudioBecomingNoisy(true).setWakeMode(C.WAKE_MODE_LOCAL).build()
        engine.repeatMode=if(options.loop) Player.REPEAT_MODE_ALL else Player.REPEAT_MODE_OFF
        engine.addListener(object: Player.Listener {
            override fun onMediaItemTransition(item: MediaItem?, reason: Int) {
                if(reason==Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT) countedIndex=-1
                scope.launch { persist() }
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if(isPlaying && countedIndex!=engine.currentMediaItemIndex) {
                    countedIndex=engine.currentMediaItemIndex
                    queue.getOrNull(countedIndex)?.let { entry -> scope.launch {
                        repo.recordPlay(entry.sourceId,entry.itemId)
                    } }
                }
                scope.launch { persist() }
            }
        })
        player=engine
        val callback=object: MediaSession.Callback {
            override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
                // External controllers may control playback, but cannot inject arbitrary URLs/files.
                val commands=Player.Commands.Builder().addAllCommands()
                    .remove(Player.COMMAND_CHANGE_MEDIA_ITEMS).remove(Player.COMMAND_SET_MEDIA_ITEM).build()
                return MediaSession.ConnectionResult.AcceptedResultBuilder(session).setAvailablePlayerCommands(commands).build()
            }
        }
        val activity=PendingIntent.getActivity(this,0,Intent(this,MainActivity::class.java),PendingIntent.FLAG_IMMUTABLE)
        if(session==null) session=MediaSession.Builder(this,engine).setCallback(callback).setSessionActivity(activity).build()
        else session?.setPlayer(engine)
    }
    private suspend fun restoreQueue(play: Boolean) {
        repo.seed()
        val saved=repo.dao.session() ?: Session()
        val entries=repo.dao.queue()
        val old=player
        old?.stop()
        buildPlayer(saved.options)
        old?.release()
        queue=entries; countedIndex=-1; loadedSessionId=saved.sessionId
        val localized=createConfigurationContext(android.content.res.Configuration(resources.configuration).apply { setLocale(java.util.Locale.forLanguageTag(repo.dao.settings()?.uiLanguage ?: "zh-TW")) })
        val ui=tw.language.listener.i18n.UiStrings(localized)
        val media=entries.map { entry ->
            val item=repo.dao.item(entry.sourceId,entry.itemId)
            MediaItem.Builder().setMediaId("${entry.sourceId}/${entry.itemId}")
                .setUri(Uri.Builder().scheme("speech").authority("local").appendQueryParameter("source",entry.sourceId).appendQueryParameter("item",entry.itemId).build())
                .setMediaMetadata(MediaMetadata.Builder().setTitle(item?.text(saved.options.target)?.take(160) ?: ui.message("尚未下載教材"))
                    .setArtist(item?.category?.take(80) ?: ui.message("請同步來源")).setAlbumTitle("Language Listener").build()).build()
        }
        if(media.isNotEmpty()) {
            player?.setMediaItems(media,saved.position.coerceIn(media.indices),saved.offsetMs)
            // Do not synthesize or resume audio unexpectedly after process restart.
            if(play) { player?.prepare(); player?.play() }
        }
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if(intent?.action==ACTION_RELOAD) {
            loadJob?.cancel()
            loadJob=scope.launch { restoreQueue(intent.getBooleanExtra("play",false)) }
        }
        return super.onStartCommand(intent,flags,startId)
    }
    private suspend fun persist() {
        val engine=player ?: return
        if(queue.isNotEmpty() && engine.mediaItemCount>0) repo.savePosition(engine.currentMediaItemIndex,engine.currentPosition,loadedSessionId)
    }
    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession?=session
    override fun onTaskRemoved(rootIntent: Intent?) {
        if(player?.playWhenReady!=true) stopSelf()
    }
    override fun onDestroy() {
        session?.release(); player?.release(); scope.cancel(); super.onDestroy()
    }
    companion object { const val ACTION_RELOAD="tw.language.listener.RELOAD" }
}
