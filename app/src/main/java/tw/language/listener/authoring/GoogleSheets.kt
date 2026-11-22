package tw.language.listener.authoring

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.*
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import tw.language.listener.data.*
import tw.language.listener.sync.SheetColumns
import java.io.File
import java.net.URI
import java.util.UUID

data class SheetLink(val spreadsheetId: String, val gid: Int?) {
    companion object {
        fun parse(url: String): SheetLink {
            val uri=URI(url.trim())
            require(uri.scheme=="https" && uri.host=="docs.google.com" && uri.userInfo==null && uri.port==-1) { "請輸入 Google Sheets HTTPS 原始試算表網址" }
            val match=Regex("/spreadsheets/d/([A-Za-z0-9_-]+)(?:/.*)?").matchEntire(uri.path)
            require(match!=null && match.groupValues[1]!="e") { "授權讀寫需使用原始試算表網址，不能使用發布 CSV 網址" }
            val gidText=Regex("(?:^|[&#])gid=([^&#]*)").find((uri.rawQuery ?: "")+"&"+(uri.rawFragment ?: ""))?.groupValues?.get(1)
            val gid=gidText?.toIntOrNull()
            require(gidText==null || (gid!=null && gid>=0)) { "工作表 gid 不合法" }
            return SheetLink(match.groupValues[1],gid)
        }
    }
}

data class SheetPlan(val spreadsheetId: String, val sheetId: Int, val title: String,
    val headers: List<String>, val columns: Int, val rows: Int, val name: String,val requiredHeaders: List<String> = GoogleSheets.HEADERS) {
    val url get()="https://docs.google.com/spreadsheets/d/$spreadsheetId/export?format=csv&gid=$sheetId"
    val sourceId get()=UUID.nameUUIDFromBytes(url.toByteArray(Charsets.UTF_8)).toString()
    val missing get()=requiredHeaders.filter { required -> headers.none { it.equals(required,true) } }
    val rangeTitle get()="'${title.replace("'","''")}'"
    fun source()=Source(sourceId,name.ifBlank { title },url,access="google")
}
data class AppendResult(val written: Int, val skipped: Int)

data class CloudSnapshot(val plan: SheetPlan,val rows: Map<Int,List<String>>,val items: List<Item>,val hash: String)

class GoogleSheets(private val http: JsonHttp=JsonHttp(), private val base: HttpUrl="https://sheets.googleapis.com/v4/spreadsheets/".toHttpUrl()) {
    private fun endpoint(id: String, suffix: String=""): HttpUrl {
        val builder=base.newBuilder().addPathSegment(if(suffix.startsWith(":")) id+suffix else id)
        if(suffix.isNotEmpty() && !suffix.startsWith(":")) builder.addPathSegments(suffix)
        return builder.build()
    }
    private suspend fun get(url: HttpUrl, token: String)=http.request(url,"Google Sheets",mapOf("Authorization" to "Bearer $token"))
    private suspend fun post(url: HttpUrl, token: String, body: JsonObject)=http.request(url,"Google Sheets",mapOf("Authorization" to "Bearer $token"),"POST",body)
    suspend fun inspect(name: String, url: String, token: String,requiredHeaders: List<String> = HEADERS): SheetPlan {
        require(requiredHeaders.all { it in ALL_HEADERS })
        val link=SheetLink.parse(url)
        val metadata=get(endpoint(link.spreadsheetId).newBuilder().addQueryParameter("fields","sheets(properties(sheetId,title,gridProperties))").build(),token)
        val sheets=metadata["sheets"]?.jsonArray.orEmpty().map { it.jsonObject.getValue("properties").jsonObject }
        val selected=if(link.gid==null) sheets.firstOrNull() else sheets.firstOrNull { it["sheetId"]?.jsonPrimitive?.int==link.gid }
        checkNotNull(selected) { "找不到指定工作表，請檢查網址 gid" }
        val title=selected.getValue("title").jsonPrimitive.content
        val grid=selected.getValue("gridProperties").jsonObject
        val plan=SheetPlan(link.spreadsheetId,selected.getValue("sheetId").jsonPrimitive.int,title,emptyList(),
            grid.getValue("columnCount").jsonPrimitive.int,grid.getValue("rowCount").jsonPrimitive.int,name)
        val headerResult=get(endpoint(link.spreadsheetId,"values").newBuilder().addPathSegment("${plan.rangeTitle}!A1:${columnName(minOf(plan.columns,100))}1").build(),token)
        val headers=headerResult["values"]?.jsonArray?.firstOrNull()?.jsonArray.orEmpty().map { it.jsonPrimitive.content.trim().removePrefix("\uFEFF") }
        require(headers.size<=100 && headers.none { it.isBlank() } && headers.map { it.lowercase(java.util.Locale.ROOT) }.distinct().size==headers.size) { "欄位含空白、重複名稱或超過 100 欄，請先修正" }
        require(headers.isEmpty() || (headers.any { it.equals("ID",true) } && headers.any { h -> PlaybackOptions.LANGUAGES.any { it.equals(h,true) } })) {
            "此工作表已有其他用途的欄位，請選擇空白工作表或既有教材工作表；不會覆寫原內容"
        }
        if(headers.isEmpty()) ensureEmpty(plan,token)
        return plan.copy(headers=headers,requiredHeaders=requiredHeaders)
    }
    private suspend fun ensureEmpty(plan: SheetPlan, token: String) {
        require(plan.columns<=100) { "請使用 100 欄以內的空白工作表初始化" }
        var start=2
        while(start<=plan.rows) {
            currentCoroutineContext().ensureActive()
            val ranges=mutableListOf<String>()
            repeat(5) { if(start<=plan.rows) {
                val end=minOf(start+249,plan.rows)
                ranges+="${plan.rangeTitle}!A$start:${columnName(plan.columns)}$end"; start=end+1
            } }
            val url=endpoint(plan.spreadsheetId,"values:batchGet").newBuilder().apply { ranges.forEach { addQueryParameter("ranges",it) } }.build()
            val results=get(url,token)["valueRanges"]?.jsonArray
            check(results!=null && results.size==ranges.size) { "無法確認工作表是否空白，未初始化" }
            require(results.all { range -> range.jsonObject["values"]?.jsonArray.orEmpty().all { row -> row.jsonArray.all { it.jsonPrimitive.content.isBlank() } } }) {
                "工作表第一列雖空白，但下方已有資料；請新增空白工作表，避免把既有內容誤當教材"
            }
        }
    }
    suspend fun initialize(plan: SheetPlan, token: String): Source {
        val fresh=inspect(plan.name,plan.url,token,plan.requiredHeaders)
        check(fresh.headers==plan.headers) { "工作表欄位已改變，請重新檢查再初始化" }
        if(fresh.missing.isNotEmpty()) {
            val end=fresh.headers.size+fresh.missing.size
            require(end<=100) { "初始化後欄位超過 100 欄" }
            val requests=buildJsonArray {
                if(end>fresh.columns) add(buildJsonObject { putJsonObject("appendDimension") { put("sheetId",fresh.sheetId); put("dimension","COLUMNS"); put("length",end-fresh.columns) } })
                add(buildJsonObject { putJsonObject("updateCells") {
                    putJsonObject("range") { put("sheetId",fresh.sheetId); put("startRowIndex",0); put("endRowIndex",1); put("startColumnIndex",fresh.headers.size); put("endColumnIndex",end) }
                    putJsonArray("rows") { add(buildJsonObject { putJsonArray("values") {
                        fresh.missing.forEach { header -> add(buildJsonObject { putJsonObject("userEnteredValue") { put("stringValue",header) } }) }
                    } }) }
                    put("fields","userEnteredValue")
                } })
            }
            post(endpoint(fresh.spreadsheetId,":batchUpdate"),token,buildJsonObject { put("requests",requests) })
        }
        val verified=inspect(plan.name,plan.url,token,plan.requiredHeaders)
        check(verified.headers==fresh.headers+fresh.missing) { "欄位初始化結果不符，請重新檢查工作表" }
        SheetColumns(verified.headers)
        return verified.source()
    }
    private suspend fun scan(plan: SheetPlan, token: String, networkAllowed: ()->Boolean={ true }, row: suspend (List<String>)->Unit) {
        SheetColumns(plan.headers)
        var start=2
        while(start<=plan.rows) {
            currentCoroutineContext().ensureActive()
            check(networkAllowed()) { "目前網路不符合同步政策，保留原有教材" }
            val ranges=mutableListOf<String>()
            repeat(5) {
                if(start<=plan.rows) {
                    val end=minOf(start+249,plan.rows)
                    ranges+="${plan.rangeTitle}!A$start:${columnName(plan.headers.size)}$end"
                    start=end+1
                }
            }
            val url=endpoint(plan.spreadsheetId,"values:batchGet").newBuilder().addQueryParameter("valueRenderOption","FORMATTED_VALUE").apply {
                ranges.forEach { addQueryParameter("ranges",it) }
            }.build()
            val result=get(url,token)["valueRanges"]?.jsonArray
            check(result!=null && result.size==ranges.size) { "Google Sheets 未回傳完整範圍，保留原有離線教材" }
            for(range in result) for(value in range.jsonObject["values"]?.jsonArray.orEmpty()) {
                val cells=value.jsonArray.map { it.jsonPrimitive.content }
                require(cells.size<=plan.headers.size)
                val padded=cells+List(plan.headers.size-cells.size) { "" }
                if(padded.any { it.isNotBlank() }) row(padded)
            }
        }
    }
    suspend fun download(source: Source, token: String, file: File, networkAllowed: ()->Boolean={ true }) {
        val plan=inspect(source.name,source.url,token)
        file.bufferedWriter().use { output ->
            output.appendLine(csv(plan.headers))
            scan(plan,token,networkAllowed) { output.appendLine(csv(it)) }
        }
    }
    suspend fun append(source: Source, items: List<Item>, token: String, beforeWrite: ()->Unit={}): AppendResult {
        require(source.access=="google" && items.isNotEmpty() && items.size<=100)
        require(items.all { it.sourceId==source.sourceId } && items.map { it.itemId }.distinct().size==items.size)
        val plan=inspect(source.name,source.url,token)
        val columns=SheetColumns(plan.headers)
        require(EXTRA_HEADERS.none { header -> items.any { it.text(header).isNotBlank() } && plan.headers.none { it.equals(header,true) } }) { "工作表缺少日文或韓文欄位，請先重新初始化" }
        val wanted=items.associateBy { it.itemId }
        val desiredKeys=items.map { AuthoringValidation.fingerprint(it) }.toSet()
        val foundIds=mutableMapOf<String,String>(); val foundKeys=mutableSetOf<String>()
        scan(plan,token) { cells ->
            val existing=columns.item(source.sourceId,cells)
            val key=AuthoringValidation.fingerprint(existing)
            if(existing.itemId in wanted) foundIds[existing.itemId]=key
            if(key in desiredKeys) foundKeys+=key
        }
        items.forEach { item -> foundIds[item.itemId]?.let { check(it==AuthoringValidation.fingerprint(item)) { "教材 ID 與工作表內容衝突，請重新審核" } } }
        val pending=items.filter { it.itemId !in foundIds && AuthoringValidation.fingerprint(it) !in foundKeys }
        if(pending.isEmpty()) return AppendResult(0,items.size)
        val rows=pending.map { item -> JsonArray(plan.headers.map { JsonPrimitive(cell(item,it)) }) }
        val url=endpoint(plan.spreadsheetId,"values").newBuilder().addPathSegment("${plan.rangeTitle}!A:${columnName(plan.headers.size)}:append")
            .addQueryParameter("valueInputOption","RAW").addQueryParameter("insertDataOption","INSERT_ROWS").build()
        beforeWrite()
        val result=post(url,token,buildJsonObject { put("majorDimension","ROWS"); put("values",JsonArray(rows)) })
        check(result["updates"]?.jsonObject?.get("updatedRows")?.jsonPrimitive?.int==pending.size) { "寫入結果未確認，請保留草稿重試；系統會以穩定 ID 避免重複寫入" }
        return AppendResult(pending.size,items.size-pending.size)
    }
    suspend fun snapshot(source: Source,token: String): CloudSnapshot {
        val plan=inspect(source.name,source.url,token)
        val columns=SheetColumns(plan.headers)
        val rows=sortedMapOf<Int,List<String>>()
        var start=2
        while(start<=plan.rows) {
            currentCoroutineContext().ensureActive()
            val ranges=mutableListOf<Pair<Int,String>>()
            repeat(5) { if(start<=plan.rows) {
                val end=minOf(start+249,plan.rows)
                ranges+=start to "${plan.rangeTitle}!A$start:${columnName(plan.headers.size)}$end";start=end+1
            } }
            val result=get(endpoint(plan.spreadsheetId,"values:batchGet").newBuilder().addQueryParameter("valueRenderOption","FORMATTED_VALUE").apply { ranges.forEach { addQueryParameter("ranges",it.second) } }.build(),token)["valueRanges"]?.jsonArray
            check(result!=null && result.size==ranges.size) { "Google Sheets 未回傳完整範圍，保留原有離線教材" }
            result.forEachIndexed { index,range ->
                range.jsonObject["values"]?.jsonArray.orEmpty().forEachIndexed { offset,row ->
                    val cells=row.jsonArray.map { it.jsonPrimitive.content }
                    require(cells.size<=plan.headers.size)
                    if(cells.any(String::isNotBlank)) rows[ranges[index].first+offset-1]=cells+List(plan.headers.size-cells.size) { "" }
                }
            }
        }
        val items=rows.values.map { columns.item(source.sourceId,it) }
        require(items.map { it.itemId }.distinct().size==items.size) { "雲端含重複教材 ID，未同步" }
        val hash=tw.language.listener.documents.DocumentCodec.hash(buildJsonObject { put("headers",JsonArray(plan.headers.map(::JsonPrimitive)));putJsonObject("rows") { rows.forEach { (index,values) -> put(index.toString(),JsonArray(values.map(::JsonPrimitive))) } } }.toString().toByteArray())
        return CloudSnapshot(plan,rows,items,hash)
    }
    suspend fun writeMerged(source: Source,expected: CloudSnapshot,items: List<Item>,token: String) {
        require(items.all { it.sourceId==source.sourceId } && items.map { it.itemId }.distinct().size==items.size)
        val fresh=snapshot(source,token)
        check(fresh.hash==expected.hash) { "Google Sheets 已被修改，請重新預覽，未覆寫" }
        val plan=fresh.plan;val idColumn=plan.headers.indexOfFirst { it.equals("ID",true) }
        val indices=fresh.rows.entries.filter { it.value[idColumn].isNotBlank() }.associate { it.value[idColumn] to it.key }
        val old=fresh.items.associateBy { it.itemId };var appendAt=(fresh.rows.keys.maxOrNull() ?: 0)+1
        val updates=mutableListOf<JsonObject>();var allocatedRows=plan.rows;var bytes=0
        suspend fun flush() {
            if(updates.isNotEmpty()) {
                post(endpoint(plan.spreadsheetId,":batchUpdate"),token,buildJsonObject { put("requests",JsonArray(updates.toList())) })
                updates.clear();bytes=0
            }
        }
        for(item in items) {
            if(old[item.itemId]==item) continue
            val row=indices[item.itemId] ?: appendAt++
            if(row+1>allocatedRows) {
                updates+=buildJsonObject { putJsonObject("appendDimension") { put("sheetId",plan.sheetId);put("dimension","ROWS");put("length",row+1-allocatedRows) } }
                allocatedRows=row+1
            }
            plan.headers.forEachIndexed { col,header -> if(ALL_HEADERS.any { it.equals(header,true) }) {
                val request=buildJsonObject { putJsonObject("updateCells") {
                    putJsonObject("range") { put("sheetId",plan.sheetId);put("startRowIndex",row);put("endRowIndex",row+1);put("startColumnIndex",col);put("endColumnIndex",col+1) }
                    putJsonArray("rows") { add(buildJsonObject { putJsonArray("values") { add(buildJsonObject { putJsonObject("userEnteredValue") { put("stringValue",cell(item,header)) } }) } }) }
                    put("fields","userEnteredValue")
                } }
                val size=request.toString().toByteArray().size
                if(updates.size>=100 || bytes+size>1024*1024) flush()
                updates+=request;bytes+=size
            } }
        }
        flush()
        val verified=snapshot(source,token).items.associateBy { it.itemId }
        check(items.all { verified[it.itemId]==it }) { "雲端寫入結果未確認，請保留本機教材並重新同步" }
    }
    companion object {
        val HEADERS=listOf("ID","Category","Type","zh-TW","en-US","id-ID","level","tags","enabled","updated_at","status","source_url","pronunciation_note")
        val EXTRA_HEADERS=listOf("ja-JP","ko-KR")
        val ALL_HEADERS=HEADERS+EXTRA_HEADERS
        fun columnName(number: Int): String {
            require(number in 1..100)
            var n=number; var result=""
            while(n>0) { n--; result=('A'+n%26)+result; n/=26 }
            return result
        }
        fun csv(values: List<String>)=values.joinToString(",") { "\"${it.replace("\"","\"\"")}\"" }
        fun cell(item: Item, header: String)=when(header.lowercase(java.util.Locale.ROOT)) {
            "id"->item.itemId; "category"->item.category; "type"->item.type; "zh-tw"->item.zh; "en-us"->item.en; "id-id"->item.id;"ja-jp"->item.ja;"ko-kr"->item.ko
            "level"->item.level; "tags"->item.tags; "enabled"->item.enabled.toString(); "updated_at"->item.updatedAt; "status"->item.status
            "source_url"->item.sourceUrl; "pronunciation_note"->item.pronunciationNote; else->""
        }
    }
}
