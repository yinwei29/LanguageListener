package tw.language.listener.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface ListenerDao {
    @Query("SELECT * FROM sources ORDER BY name") fun observeSources(): Flow<List<Source>>
    @Query("SELECT * FROM sources ORDER BY name") suspend fun sources(): List<Source>
    @Upsert suspend fun putSources(values: List<Source>)
    @Query("DELETE FROM sources WHERE sourceId=:id") suspend fun deleteSource(id: String)
    @Query("UPDATE items SET deleted=1 WHERE sourceId=:id") suspend fun softDeleteSource(id: String)
    @Upsert suspend fun putItems(values: List<Item>)
    @Upsert suspend fun putProgress(values: List<Progress>)
    @Upsert suspend fun putSettings(value: Settings)
    @Upsert suspend fun putDays(values: List<StudyDay>)
    @Upsert suspend fun putSession(value: Session)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putQueue(values: List<QueueEntry>)
    @Query("SELECT * FROM settings WHERE singleton=1") suspend fun settings(): Settings?
    @Query("SELECT * FROM settings WHERE singleton=1") fun observeSettings(): Flow<Settings?>
    @Query("SELECT * FROM session WHERE singleton=1") suspend fun session(): Session?
    @Query("SELECT * FROM session WHERE singleton=1") fun observeSession(): Flow<Session?>
    @Query("SELECT * FROM queue ORDER BY position") suspend fun queue(): List<QueueEntry>
    @Query("SELECT * FROM queue ORDER BY position LIMIT 1 OFFSET :index") suspend fun queueAt(index: Int): QueueEntry?
    @Query("SELECT COUNT(*) FROM queue") suspend fun queueCount(): Int
    @Query("SELECT COUNT(*) FROM queue") fun observeQueueSize(): Flow<Int>
    @Query("DELETE FROM queue") suspend fun clearQueue()
    @Query("SELECT * FROM items WHERE sourceId=:source AND itemId=:id") suspend fun item(source: String, id: String): Item?
    @Query("SELECT * FROM progress WHERE sourceId=:source AND itemId=:id") suspend fun progress(source: String, id: String): Progress?
    @Query("SELECT * FROM progress WHERE sourceId=:source AND itemId=:id") fun observeProgress(source: String, id: String): Flow<Progress?>
    @Query("SELECT * FROM items ORDER BY sourceId,itemId LIMIT :limit OFFSET :offset") suspend fun itemPage(limit: Int, offset: Int): List<Item>
    @Query("SELECT * FROM progress ORDER BY sourceId,itemId LIMIT :limit OFFSET :offset") suspend fun progressPage(limit: Int, offset: Int): List<Progress>
    @Query("SELECT COUNT(*) AS total, COALESCE(SUM(COALESCE(p.learned,0)),0) AS learned, COALESCE(SUM(COALESCE(p.favorite,0)),0) AS favorite FROM items i LEFT JOIN progress p USING(sourceId,itemId) WHERE i.enabled=1 AND i.deleted=0") fun stats(): Flow<Stats>
    @Query("SELECT epochDay FROM study_days ORDER BY epochDay DESC") fun playedDays(): Flow<List<Long>>
    @Query("SELECT * FROM study_days ORDER BY epochDay") suspend fun days(): List<StudyDay>
    @Query("DELETE FROM study_days") suspend fun clearDays()
    @Query("SELECT category,type,COUNT(*) AS total FROM items WHERE enabled=1 AND deleted=0 GROUP BY category,type ORDER BY category,type") fun categories(): Flow<List<CategoryCount>>
    @Query("SELECT * FROM items WHERE deleted=0 AND enabled=1 AND (:category='' OR category=:category) AND (zh LIKE '%' || :search || '%' OR en LIKE '%' || :search || '%' OR `id` LIKE '%' || :search || '%') ORDER BY category,itemId LIMIT :limit OFFSET :offset")
    suspend fun search(search: String, category: String, limit: Int, offset: Int): List<Item>

    // Only stable keys enter the queue; texts are fetched one item at a time by the audio loader.
    @Query("""INSERT INTO queue(sourceId,itemId)
        SELECT i.sourceId,i.itemId
        FROM items i LEFT JOIN progress p USING(sourceId,itemId)
        WHERE i.enabled=1 AND i.deleted=0 AND COALESCE(p.skipUntil,0)<=:now
          AND (:excludeLearned=0 OR COALESCE(p.learned,0)=0)
          AND (:category='' OR i.category=:category) AND (:type='' OR i.type=:type) AND (:level='' OR i.level=:level)
          AND (:state='all' OR (:state='learned' AND p.learned=1) OR (:state='favorite' AND p.favorite=1) OR (:state='review' AND p.reviewPriority>0))
          AND COALESCE(p.lastPlayedAt,0)<=:recentCutoff
          AND (CASE :nativeLanguage WHEN 'zh-TW' THEN i.zh WHEN 'en-US' THEN i.en ELSE i.`id` END)!=''
          AND (CASE :target WHEN 'zh-TW' THEN i.zh WHEN 'en-US' THEN i.en ELSE i.`id` END)!=''
        ORDER BY CASE WHEN :reviewFirst THEN COALESCE(p.reviewPriority,0) ELSE 0 END DESC,
          CASE WHEN :random THEN RANDOM() ELSE 0 END,i.sourceId,i.itemId
        LIMIT :limit""")
    suspend fun fillQueue(now: Long, recentCutoff: Long, excludeLearned: Boolean, category: String,
        type: String, level: String, state: String, nativeLanguage: String, target: String,
        reviewFirst: Boolean, random: Boolean, limit: Int)
    @Query("DELETE FROM items") suspend fun clearItems()
    @Query("DELETE FROM progress") suspend fun clearProgress()
    @Query("DELETE FROM sources") suspend fun clearSources()
    @Query("DELETE FROM session") suspend fun clearSession()
    @Query("DELETE FROM settings") suspend fun clearSettings()
}

@Database(entities = [Source::class, Item::class, Progress::class, QueueEntry::class, StudyDay::class, Session::class, Settings::class], version = 1, exportSchema = true)
@TypeConverters(Converters::class)
abstract class ListenerDatabase : RoomDatabase() { abstract fun dao(): ListenerDao }
