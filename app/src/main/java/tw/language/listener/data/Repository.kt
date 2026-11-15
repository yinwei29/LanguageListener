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
    suspend fun saveSettings(settings: Settings) = mutation.withLock {
        settings.playback.validate()
        dao.putSettings(settings.copy(modifiedAt=System.currentTimeMillis()))
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
        db.withTransaction { dao.softDeleteSource(id); dao.deleteSource(id) }
        syncEpoch.incrementAndGet()
    }
    companion object {
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
