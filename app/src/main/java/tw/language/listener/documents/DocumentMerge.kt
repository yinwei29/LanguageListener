package tw.language.listener.documents

import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import tw.language.listener.data.*

object DocumentMerge {
    fun encode(items: List<Item>)=JsonCodec.json.encodeToString(items.sortedBy { it.itemId })
    fun decode(value: String)=JsonCodec.json.decodeFromString<List<Item>>(value)
    fun digest(items: List<Item>)=DocumentCodec.hash(encode(items).toByteArray())
    /** Absence does not delete a lesson. Use enabled/status for explicit removal. */
    fun merge(base: List<Item>, app: List<Item>, external: List<Item>): List<Item> {
        val previous=base.associateBy(Item::itemId);val local=app.associateBy(Item::itemId);val remote=external.associateBy(Item::itemId)
        require(previous.size==base.size && local.size==app.size && remote.size==external.size) { "教材 ID 重複，未同步" }
        return (local.keys+remote.keys).sorted().map { id ->
            val a=local[id];val b=remote[id];val old=previous[id]
            check(a==null || b==null || a==b || a==old || b==old) { "同一教材在兩邊都有不同修改，未覆寫；請先將內容調整一致再同步" }
            when { a==null->b!!;b==null->a;a==old->b;else->a }
        }
    }
}
