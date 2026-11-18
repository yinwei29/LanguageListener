package tw.language.listener.documents

import androidx.room.*

/** Device-only URI permission and merge bases; deliberately excluded from ZIP backups. */
@Entity(tableName="local_documents")
data class LocalDocument(@PrimaryKey val sourceId: String,val uri: String,val sheet: String="",val fileHash: String,
    val fileBase: String="[]",val cloudBase: String="[]")
