package tw.language.listener.data

import androidx.room.*
import kotlinx.serialization.Serializable

@Serializable @Entity(tableName = "sources")
data class Source(@PrimaryKey val sourceId: String, val name: String, val url: String,
    val lastSync: Long = 0, val error: String? = null,
    @ColumnInfo(defaultValue = "'public'") val access: String = "public")

@Entity(tableName = "ai_drafts")
data class DraftRecord(@PrimaryKey val singleton: Int = 1, val payload: String)

@Serializable @Entity(tableName = "items", primaryKeys = ["sourceId", "itemId"],
    indices = [Index("category"), Index("type"), Index("sourceId")])
data class Item(val sourceId: String, val itemId: String, val category: String = "日常",
    val type: String = "word", val zh: String, val en: String, val id: String,
    val level: String = "", val tags: String = "", val enabled: Boolean = true,
    val deleted: Boolean = false, val updatedAt: String = "", val status: String = "",
    val sourceUrl: String = "", val pronunciationNote: String = "",
    @ColumnInfo(defaultValue="''") val ja: String="",@ColumnInfo(defaultValue="''") val ko: String="") {
    fun text(language: String) = when(language) { "zh-TW" -> zh; "en-US" -> en; "id-ID" -> id; "ja-JP"->ja; "ko-KR"->ko;else -> "" }
}

@Serializable @Entity(tableName = "progress", primaryKeys = ["sourceId", "itemId"])
data class Progress(val sourceId: String, val itemId: String, val learned: Boolean = false,
    val favorite: Boolean = false, val skipUntil: Long = 0, val reviewPriority: Int = 0,
    val playCount: Long = 0, val lastPlayedAt: Long = 0, val modifiedAt: Long = 0,
    @ColumnInfo(defaultValue="0") val nextReviewAt: Long=0,
    @ColumnInfo(defaultValue="0") val lastReviewedAt: Long=0,
    @ColumnInfo(defaultValue="0") val intervalDays: Int=0,
    @ColumnInfo(defaultValue="2.5") val ease: Double=2.5,
    @ColumnInfo(defaultValue="0") val repetitions: Int=0,
    @ColumnInfo(defaultValue="0") val lapses: Int=0)

@Serializable @Entity(tableName="review_events",indices=[Index("epochDay")])
data class ReviewEvent(@PrimaryKey val eventId: String,val sourceId: String,val itemId: String,
    val reviewedAt: Long,val epochDay: Long,val rating: tw.language.listener.review.Rating)
data class DueSummary(val due: Int=0,val scheduled: Int=0)
data class ReviewDay(val epochDay: Long,val total: Int,val again: Int)

@Serializable @Entity(tableName = "queue")
data class QueueEntry(@PrimaryKey(autoGenerate = true) val position: Int, val sourceId: String, val itemId: String)

@Serializable @Entity(tableName = "study_days")
data class StudyDay(@PrimaryKey val epochDay: Long)

@Serializable @Entity(tableName = "session")
data class Session(@PrimaryKey val singleton: Int = 1, val position: Int = 0,
    val offsetMs: Long = 0, val options: PlaybackOptions = PlaybackOptions(),
    val sessionId: String = java.util.UUID.randomUUID().toString())

@Serializable @Entity(tableName = "settings")
data class Settings(@PrimaryKey val singleton: Int = 1, val playback: PlaybackOptions = PlaybackOptions(),
    val wifiOnly: Boolean = false, val syncOnLaunch: Boolean = true,
    @androidx.room.ColumnInfo(defaultValue = "'zh-TW'") val uiLanguage: String = "zh-TW",
    val dark: Boolean = false, val modifiedAt: Long = 0, val aiProfile: tw.language.listener.authoring.AiProfile? = null)

@Serializable
data class PlaybackOptions(val native: String = "zh-TW", val target: String = "en-US",
    val direction: String = "native-target", val thirdLanguage: Boolean = true,
    val zhRate: Float = 1f, val enRate: Float = .85f, val idRate: Float = .85f,
    val repeats: Int = 1, val gapSeconds: Int = 2, val loop: Boolean = false,
    val count: Int = 20, val random: Boolean = false, val excludeLearned: Boolean = true,
    val category: String = "", val type: String = "", val level: String = "",
    val sourceId: String="",val jaRate: Float=.85f,val koRate: Float=.85f,
    val state: String = "all", val avoidRecentDays: Int = 0, val reviewFirst: Boolean = true) {
    fun rate(language: String) = when(language) { "zh-TW" -> zhRate; "en-US" -> enRate; "ja-JP"->jaRate;"ko-KR"->koRate;else -> idRate }
    fun languages() = when(direction) {
        "target-native" -> listOf(target, native)
        "target" -> listOf(target)
        else -> listOf(native, target)
    }
    fun validate() {
        require(native in LANGUAGES && target in LANGUAGES && native != target) { "請選擇不同的母語及目標語" }
        require(direction in listOf("native-target", "target-native", "target"))
        require(listOf(zhRate, enRate, idRate,jaRate,koRate).all { it.isFinite() && it in .25f..2f })
        require(repeats in 1..10 && gapSeconds in 0..30 && count >= 0 && avoidRecentDays in 0..365)
        require(sourceId.length<=512 && sourceId.none(Char::isISOControl))
        require(state in listOf("all", "learned", "favorite", "review", "due"))
    }
    companion object { val CORE_LANGUAGES=listOf("zh-TW", "en-US", "id-ID");val LANGUAGES=CORE_LANGUAGES+listOf("ja-JP","ko-KR");val LANGUAGE_LABELS=listOf("zh-TW" to "繁體中文","en-US" to "English","id-ID" to "Bahasa Indonesia","ja-JP" to "日本語","ko-KR" to "한국어") }
}

class Converters {
    @TypeConverter fun encodeProfile(value: tw.language.listener.authoring.AiProfile?): String? = value?.let { JsonCodec.json.encodeToString(tw.language.listener.authoring.AiProfile.serializer(),it) }
    @TypeConverter fun decodeProfile(value: String?): tw.language.listener.authoring.AiProfile? = value?.let { JsonCodec.json.decodeFromString(tw.language.listener.authoring.AiProfile.serializer(),it) }
    @TypeConverter fun encode(value: PlaybackOptions): String = JsonCodec.json.encodeToString(PlaybackOptions.serializer(), value)
    @TypeConverter fun decode(value: String): PlaybackOptions = JsonCodec.json.decodeFromString(PlaybackOptions.serializer(), value)
}
object JsonCodec { val json = kotlinx.serialization.json.Json { encodeDefaults = true; ignoreUnknownKeys = false } }

data class Stats(val total: Int, val learned: Int, val favorite: Int)
data class CategoryCount(val category: String, val type: String, val total: Int)
