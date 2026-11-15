package tw.language.listener.sync

import androidx.room.withTransaction
import kotlinx.coroutines.sync.withLock
import tw.language.listener.data.*
import java.io.Reader
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

data class SyncDelta(val upserted: Int = 0, val deleted: Int = 0)

/** A complete snapshot becomes visible atomically; progress is never part of this transaction. */
class SheetImporter(private val repo: Repository) {
    suspend fun import(source: Source, input: Reader, expectedEpoch: Long=repo.syncEpoch.get()): SyncDelta? = repo.mutation.withLock {
        // Reject a response started before removal/re-addition, restore or another successful sync.
        if(repo.syncEpoch.get()!=expectedEpoch || repo.dao.sources().find { it.sourceId==source.sourceId }!=source) return@withLock null
        repo.db.withTransaction {
            val csv=CsvReader(input)
            val columns=SheetColumns(requireNotNull(csv.row()) { "CSV 為空" })
            val sql=repo.db.openHelper.writableDatabase
            // Only IDs enter this connection-local table, keeping full text batches bounded.
            sql.execSQL("CREATE TEMP TABLE sync_keys(itemId TEXT PRIMARY KEY NOT NULL)")
            try {
                var writes=0
                val batch=linkedMapOf<String,Item>()
                suspend fun flush() {
                    if(batch.isEmpty()) return
                    currentCoroutineContext().ensureActive()
                    val existing=repo.dao.itemsByIds(source.sourceId,batch.keys.toList()).associateBy { it.itemId }
                    val changed=batch.values.filter { existing[it.itemId]!=it }
                    if(changed.isNotEmpty()) repo.dao.putItems(changed)
                    writes+=changed.size
                    sql.compileStatement("INSERT OR IGNORE INTO temp.sync_keys(itemId) VALUES (?)").use { statement ->
                        batch.keys.forEach { statement.bindString(1,it); statement.executeInsert(); statement.clearBindings() }
                    }
                    batch.clear()
                }
                var rows=0
                while(true) {
                    currentCoroutineContext().ensureActive()
                    val row=csv.row() ?: break
                    if(row.all { it.isBlank() }) continue
                    val item=columns.item(source.sourceId,row)
                    batch[item.itemId]=item
                    if(++rows%250==0) flush()
                }
                flush()
                sql.execSQL("UPDATE items SET deleted=1 WHERE sourceId=? AND deleted=0 AND itemId NOT IN (SELECT itemId FROM temp.sync_keys)",arrayOf(source.sourceId))
                val deleted=sql.query("SELECT changes()").use { it.moveToFirst(); it.getInt(0) }
                repo.dao.putSources(listOf(source.copy(lastSync=System.currentTimeMillis(),error=null)))
                SyncDelta(writes,deleted)
            } finally {
                sql.execSQL("DROP TABLE temp.sync_keys")
            }
        }
    }
}
