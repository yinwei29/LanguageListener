package tw.language.listener.data

import androidx.room.withTransaction
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.LocalDate
import java.util.UUID

class Repository(val db: ListenerDatabase) {
    val dao = db.dao()
    // Shared by sync, restore, session creation and player state writes.
    val mutation = Mutex()
    // In-flight requests cannot outlive a source removal/re-add or backup restore.
    val syncEpoch = java.util.concurrent.atomic.AtomicLong(0)
    suspend fun seed() = mutation.withLock {
        db.withTransaction {
            if (dao.settings() == null) {
                dao.putSettings(Settings())
                dao.putSources(listOf(Source("demo", "三語示範教材", "")))
            }
            if(dao.sources().any { it.sourceId=="demo" } && dao.item("demo","hello")==null) {
                dao.putItems(listOf(
                    Item("demo", "hello", zh="你好", en="Hello", id="Halo"),
                    Item("demo", "thanks", zh="謝謝", en="Thank you", id="Terima kasih"),
                    Item("demo", "water", zh="水", en="Water", id="Air"),
                    Item("demo", "check-in", "酒店", "sentence", "我想辦理入住。", "I would like to check in.", "Saya ingin check-in."),
                    Item("demo", "station", "旅遊", "sentence", "車站在哪裡？", "Where is the station?", "Di mana stasiun?")
                ))
            }
        }
    }
    /** Device-owned lessons never participate in a remote snapshot. */
    suspend fun saveLocalItem(value: Item) = mutation.withLock {
        val selected=dao.sources().find { it.sourceId==value.sourceId }
        require((value.sourceId==LOCAL_SOURCE_ID || selected?.access=="file") && value.itemId.isNotBlank() && value.itemId.length<=512)
        require(listOf(value.zh,value.en,value.id).count { it.isNotBlank() }>=2) { "請至少填寫兩種語言的教材文字" }
        require(listOf(value.zh,value.en,value.id).all { it.length<=100_000 } && value.category.length<=512 && value.level.length<=512) { "教材欄位過長" }
        require(value.type in listOf("word","phrase","sentence"))
        db.withTransaction {
            val source=selected
            require(source==null || source.access=="file" || (source.sourceId==LOCAL_SOURCE_ID && source.url.isEmpty() && source.access=="public")) { "本機教材來源不合法" }
            if(source==null) dao.putSources(listOf(Source(LOCAL_SOURCE_ID,"我的本機教材","")))
            syncEpoch.incrementAndGet()
            dao.putItems(listOf(value.copy(zh=value.zh.trim(),en=value.en.trim(),id=value.id.trim(),category=value.category.trim().ifBlank { "日常" },enabled=true,deleted=false)))
        }
    }
    suspend fun saveSettings(settings: Settings) = mutation.withLock {
        settings.playback.validate()
        require(settings.uiLanguage in setOf("zh-TW","en","id")) { "不支援的介面語言" }
        dao.putSettings(settings.copy(modifiedAt=System.currentTimeMillis(),aiProfile=dao.settings()?.aiProfile ?: settings.aiProfile))
    }
    suspend fun createSession(options: PlaybackOptions): Int = mutation.withLock {
        options.validate()
        db.withTransaction {
            dao.clearQueue()
            val now = System.currentTimeMillis()
            dao.fillQueue(now, if(options.avoidRecentDays==0) now else now-options.avoidRecentDays*86_400_000L,
                options.excludeLearned, options.category, options.type, options.level, options.state,
                options.native, options.target, options.reviewFirst, options.random,
                if(options.count==0) -1 else options.count)
            dao.putSession(Session(options=options))
            dao.queueCount()
        }
    }
    suspend fun rate(source: String,id: String,rating: tw.language.listener.review.Rating,eventId: String,
        now: Long=System.currentTimeMillis(),zone: java.time.ZoneId=java.time.ZoneId.systemDefault()): Progress = mutation.withLock {
        UUID.fromString(eventId)
        db.withTransaction {
            val previous=dao.reviewEvent(eventId)
            if(previous!=null) {
                require(previous.sourceId==source && previous.itemId==id && previous.rating==rating) { "複習紀錄衝突，未重複評分" }
                checkNotNull(dao.progress(source,id))
            } else {
                val item=checkNotNull(dao.item(source,id)) { "教材不存在，無法安排複習" }
                check(item.enabled && !item.deleted) { "教材已停用或刪除，未記錄評分" }
                val value=tw.language.listener.review.ReviewScheduler.schedule(dao.progress(source,id) ?: Progress(source,id),rating,now,zone)
                dao.putProgress(listOf(value))
                dao.putReview(ReviewEvent(eventId,source,id,now,java.time.Instant.ofEpochMilli(now).atZone(zone).toLocalDate().toEpochDay(),rating))
                dao.putDays(listOf(StudyDay(java.time.Instant.ofEpochMilli(now).atZone(zone).toLocalDate().toEpochDay())))
                value
            }
        }
    }
    suspend fun updateProgress(source: String, id: String, transform: (Progress)->Progress) = mutation.withLock {
        db.withTransaction {
            val old = dao.progress(source,id) ?: Progress(source,id)
            dao.putProgress(listOf(transform(old).copy(modifiedAt=System.currentTimeMillis())))
        }
    }
    suspend fun savePosition(position: Int, offset: Long, expectedSessionId: String) = mutation.withLock {
        dao.session()?.takeIf { it.sessionId==expectedSessionId }?.let {
            dao.putSession(it.copy(position=position, offsetMs=offset.coerceAtLeast(0)))
        }
    }
    suspend fun recordPlay(source: String, id: String) = mutation.withLock {
        db.withTransaction {
            val old=dao.progress(source,id) ?: Progress(source,id)
            val now=System.currentTimeMillis()
            dao.putProgress(listOf(old.copy(playCount=old.playCount+1,lastPlayedAt=now,modifiedAt=now)))
            dao.putDays(listOf(StudyDay(LocalDate.now().toEpochDay())))
        }
    }
    suspend fun addSource(name: String, url: String) = mutation.withLock {
        val normalized = tw.language.listener.sync.SheetUrl.normalize(url)
        require(dao.sources().none { it.url == normalized }) { "此來源已存在" }
        dao.putSources(listOf(Source(UUID.nameUUIDFromBytes(normalized.toByteArray(Charsets.UTF_8)).toString(),name.ifBlank { "Google Sheets" },normalized)))
        syncEpoch.incrementAndGet()
    }
    suspend fun removeSource(id: String) = mutation.withLock {
        db.withTransaction { dao.softDeleteSource(id); dao.deleteSource(id); dao.deleteDocument(id) }
        syncEpoch.incrementAndGet()
    }
    companion object {
        const val LOCAL_SOURCE_ID="local-lessons"
        fun streakDays(days: List<Long>, today: LocalDate = LocalDate.now()): Int {
            val unique=days.toSet()
            var day=today.toEpochDay()
            if(day !in unique) day--
            var count=0
            while(day in unique) { count++; day-- }
            return count
        }
    }
}
