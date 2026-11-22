package tw.language.listener.sync

import java.io.PushbackReader
import java.io.Reader
import java.net.URI
import tw.language.listener.data.Item

/** Streaming RFC 4180 reader: embedded commas/newlines and escaped double quotes. */
class CsvReader(reader: Reader) {
    private val input = PushbackReader(reader, 1)
    fun row(): List<String>? {
        val fields = mutableListOf<String>()
        val value = StringBuilder()
        var quoted = false
        var closed = false
        var started = false
        while(true) {
            val code=input.read()
            if(code==-1) {
                require(!quoted) { "CSV 引號未關閉" }
                return if(!started && fields.isEmpty()) null else fields + value.toString()
            }
            started=true
            val c=code.toChar()
            if(quoted) {
                if(c=='"') {
                    val next=input.read()
                    if(next=='"'.code) value.append('"')
                    else { quoted=false; closed=true; if(next!=-1) input.unread(next) }
                } else value.append(c)
            } else when(c) {
                '"' -> { require(value.isEmpty() && !closed) { "CSV 引號格式錯誤" }; quoted=true }
                ',' -> { fields+=value.toString(); value.clear(); closed=false }
                '\r', '\n' -> {
                    if(c=='\r') { val next=input.read(); if(next!=-1 && next!='\n'.code) input.unread(next) }
                    return fields+value.toString()
                }
                else -> { require(!closed) { "CSV 引號後含有非法內容" }; value.append(c) }
            }
            require(value.length<=100_000) { "CSV 單一欄位過長" }
            require(fields.size<=100) { "CSV 欄位過多" }
        }
    }
}

class SheetColumns(header: List<String>) {
    private val names=header.map { it.trim().removePrefix("\uFEFF").lowercase(java.util.Locale.ROOT) }
    init {
        require(names.distinct().size==names.size) { "CSV 欄位名稱重複" }
        require("id" in names && tw.language.listener.data.PlaybackOptions.LANGUAGES.any { it.lowercase(java.util.Locale.ROOT) in names }) { "缺少 ID 或支援的語言欄位" }
    }
    fun item(source: String, row: List<String>): Item {
        require(row.size==names.size) { "CSV 欄位數不符" }
        fun get(name: String): String {
            val index=names.indexOf(name)
            return if(index<0) "" else row[index].trim()
        }
        val id=get("id")
        require(id.isNotBlank() && id.length<=512) { "教材 ID 不可空白或過長" }
        val enabled=get("enabled").lowercase(java.util.Locale.ROOT)
        require(enabled in listOf("", "true", "false", "1", "0", "yes", "no")) { "enabled 必須為 true/false" }
        val status=get("status")
        val zh=get("zh-tw"); val en=get("en-us"); val indonesian=get("id-id")
        require(listOf(zh,en,indonesian,get("ja-jp"),get("ko-kr")).any { it.isNotBlank() }) { "教材至少需有一種語言文字" }
        return Item(source,id,get("category").ifBlank { "日常" },get("type").ifBlank { "word" },
            zh,en,indonesian,get("level"),get("tags"),enabled !in listOf("false","0","no"),
            status.lowercase(java.util.Locale.ROOT) in listOf("deleted","removed"),get("updated_at"),status,get("source_url"),get("pronunciation_note"),ja=get("ja-jp"),ko=get("ko-kr"))
    }
}

object SheetUrl {
    fun normalize(value: String): String {
        val uri=URI(value.trim())
        require(uri.scheme=="https" && uri.host=="docs.google.com" && uri.userInfo==null && uri.port==-1) { "請使用 HTTPS Google Sheets 公開 CSV 網址" }
        val path=uri.path
        val published=Regex("/spreadsheets/d/e/[A-Za-z0-9_-]+/pub").matches(path)
        if(published) {
            val gid=uri.rawQuery?.split('&')?.firstOrNull { it.startsWith("gid=") }?.substringAfter('=')
            require(gid==null || gid.matches(Regex("\\d+"))) { "gid 需為數字" }
            return "https://docs.google.com$path?output=csv"+(gid?.let { "&gid=$it" } ?: "")
        }
        val match=Regex("/spreadsheets/d/([A-Za-z0-9_-]+)(?:/.*)?").matchEntire(path)
        require(match!=null) { "無法辨識 Google Sheets 網址" }
        val gid=Regex("(?:^|[&#])gid=(\\d+)").find((uri.rawQuery ?: "")+"&"+(uri.rawFragment ?: ""))?.groupValues?.get(1) ?: "0"
        return "https://docs.google.com/spreadsheets/d/${match.groupValues[1]}/export?format=csv&gid=$gid"
    }
}
