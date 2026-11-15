package tw.language.listener.data

import androidx.room.*
import kotlinx.serialization.Serializable

@Serializable @Entity(tableName = "sources")
data class Source(@PrimaryKey val sourceId: String, val name: String, val url: String,
    val lastSync: Long = 0, val error: String? = null)

@Serializable @Entity(tableName = "items", primaryKeys = ["sourceId", "itemId"],
    indices = [Index("category"), Index("type"), Index("sourceId")])
data class Item(val sourceId: String, val itemId: String, val category: String = "日常",
    val type: String = "word", val zh: String, val en: String, val id: String,
    val level: String = "", val tags: String = "", val enabled: Boolean = true,
    val deleted: Boolean = false, val updatedAt: String = "", val status: String = "",
    val sourceUrl: String = "", val pronunciationNote: String = "") {
    fun text(language: String) = when(language) { "zh-TW" -> zh; "en-US" -> en; "id-ID" -> id; else -> "" }
}

@Serializable @Entity(tableName = "progress", primaryKeys = ["sourceId", "itemId"])
data class Progress(val sourceId: String, val itemId: String, val learned: Boolean = false,
    val favorite: Boolean = false, val skipUntil: Long = 0, val reviewPriority: Int = 0,
    val playCount: Long = 0, val lastPlayedAt: Long = 0, val modifiedAt: Long = 0)

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
    val dark: Boolean = false, val modifiedAt: Long = 0)

@Serializable
data class PlaybackOptions(val native: String = "zh-TW", val target: String = "en-US",
    val direction: String = "native-target", val thirdLanguage: Boolean = true,
    val zhRate: Float = 1f, val enRate: Float = .85f, val idRate: Float = .85f,
    val repeats: Int = 1, val gapSeconds: Int = 2, val loop: Boolean = false,
    val count: Int = 20, val random: Boolean = false, val excludeLearned: Boolean = true,
    val category: String = "", val type: String = "", val level: String = "",
    val state: String = "all", val avoidRecentDays: Int = 0, val reviewFirst: Boolean = true) {
    fun rate(language: String) = when(language) { "zh-TW" -> zhRate; "en-US" -> enRate; else -> idRate }
    fun languages() = when(direction) {
        "target-native" -> listOf(target, native)
        "target" -> listOf(target)
        else -> listOf(native, target)
    }
    fun validate() {
        require(native in LANGUAGES && target in LANGUAGES && native != target) { "請選擇不同的母語及目標語" }
        require(direction in listOf("native-target", "target-native", "target"))
        require(listOf(zhRate, enRate, idRate).all { it.isFinite() && it in .25f..2f })
        require(repeats in 1..10 && gapSeconds in 0..30 && count >= 0 && avoidRecentDays in 0..365)
        require(state in listOf("all", "learned", "favorite", "review"))
    }
    companion object { val LANGUAGES = listOf("zh-TW", "en-US", "id-ID") }
}

class Converters {
    @TypeConverter fun encode(value: PlaybackOptions): String = JsonCodec.json.encodeToString(PlaybackOptions.serializer(), value)
    @TypeConverter fun decode(value: String): PlaybackOptions = JsonCodec.json.decodeFromString(PlaybackOptions.serializer(), value)
}
object JsonCodec { val json = kotlinx.serialization.json.Json { encodeDefaults = true; ignoreUnknownKeys = false } }

data class Stats(val total: Int, val learned: Int, val favorite: Int)
data class CategoryCount(val category: String, val type: String, val total: Int)
