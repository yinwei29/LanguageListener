package tw.language.listener

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import tw.language.listener.authoring.*
import tw.language.listener.data.*
import tw.language.listener.sync.*
import java.io.File

class GoogleSheetsAuthoringTest {
    private val link="https://docs.google.com/spreadsheets/d/example/edit#gid=7"
    private fun inspect(api: AuthoringFixture, headers: List<String>, rows: Int=3, title: String="Lessons") {
        api.json(buildJsonObject { putJsonArray("sheets") { add(buildJsonObject { putJsonObject("properties") {
            put("sheetId",7); put("title",title); putJsonObject("gridProperties") { put("rowCount",rows); put("columnCount",26) }
        } }) } }.toString())
        api.json(buildJsonObject { if(headers.isNotEmpty()) putJsonArray("values") { add(JsonArray(headers.map(::JsonPrimitive))) } }.toString())
        if(headers.isEmpty() && rows>1) enqueueRows(api,listOf(emptyList()))
    }
    private fun enqueueRows(api: AuthoringFixture, pages: List<List<List<String>>>) {
        api.json(buildJsonObject { putJsonArray("valueRanges") { pages.forEach { page -> add(buildJsonObject {
            put("values",JsonArray(page.map { row -> JsonArray(row.map(::JsonPrimitive)) }))
        }) } } }.toString())
    }
    private fun item(source: String, id: String="draft-1", en: String="Hello")=Item(source,id,zh="你好",en=en,id="Halo",status="ai_generated_unverified")
    private fun values(item: Item)=listOf(item.itemId,item.category,item.type,item.zh,item.en,item.id,item.level,item.tags,item.enabled.toString(),item.updatedAt,item.status,item.sourceUrl,item.pronunciationNote)

    @Test fun linkParsingAndColumnNamesRejectUnrelatedOrInvalidUrls() {
        assertEquals(SheetLink("example",7),SheetLink.parse(link))
        assertNull(SheetLink.parse("https://docs.google.com/spreadsheets/d/example/edit").gid)
        for(url in listOf("http://docs.google.com/spreadsheets/d/a", "https://evil.example/spreadsheets/d/a", "https://docs.google.com/spreadsheets/d/e/published/pub", link.replace("gid=7","gid=-1"),link.replace("gid=7","gid=bad"))) {
            assertThrows(IllegalArgumentException::class.java) { SheetLink.parse(url) }
        }
        assertEquals("A",GoogleSheets.columnName(1)); assertEquals("Z",GoogleSheets.columnName(26)); assertEquals("AA",GoogleSheets.columnName(27)); assertEquals("CV",GoogleSheets.columnName(100))
    }
    @Test fun blankSheetInitializesOnlyHeaderCellsAfterReview() = runBlocking {
        AuthoringFixture().use { api ->
            inspect(api,emptyList())
            val plan=api.sheets.inspect("教材",link,"test-token")
            assertEquals(GoogleSheets.HEADERS,plan.missing)
            assertEquals(3,api.server.requestCount) // includes checking rows below the empty header
            inspect(api,emptyList()); api.json("{}"); inspect(api,GoogleSheets.HEADERS)
            val source=api.sheets.initialize(plan,"test-token")
            assertEquals("google",source.access); assertTrue(source.url.endsWith("gid=7"))
            val requests=(1..9).map { api.server.takeRequest() }
            val post=requests.single { it.method=="POST" }
            assertEquals("/v4/spreadsheets/example:batchUpdate",post.path)
            val range=Json.parseToJsonElement(post.body.readUtf8()).jsonObject["requests"]!!.jsonArray.single().jsonObject["updateCells"]!!.jsonObject["range"]!!.jsonObject
            assertEquals(0,range["startRowIndex"]!!.jsonPrimitive.int); assertEquals(1,range["endRowIndex"]!!.jsonPrimitive.int)
            assertEquals(0,range["startColumnIndex"]!!.jsonPrimitive.int)
            requests.forEach { assertEquals("Bearer test-token",it.getHeader("Authorization")); assertFalse(it.path!!.contains("test-token")) }
        }
    }
    @Test fun partialExistingHeadersAreExtendedWithoutRewritingOriginalCells() = runBlocking {
        AuthoringFixture().use { api ->
            val headers=listOf("ID","en-US","zh-TW","id-ID","Custom")
            inspect(api,headers)
            val plan=api.sheets.inspect("existing",link,"token")
            inspect(api,headers); api.json("{}"); inspect(api,headers+plan.missing)
            api.sheets.initialize(plan,"token")
            val post=(1..7).map { api.server.takeRequest() }.single { it.method=="POST" }
            val update=Json.parseToJsonElement(post.body.readUtf8()).jsonObject["requests"]!!.jsonArray.single().jsonObject["updateCells"]!!.jsonObject
            assertEquals(5,update["range"]!!.jsonObject["startColumnIndex"]!!.jsonPrimitive.int)
            assertEquals(plan.missing.size,update["rows"]!!.jsonArray.single().jsonObject["values"]!!.jsonArray.size)
        }
    }
    @Test fun japaneseKoreanSheetsInitializeAllColumnsAndKeepExistingHeaderCells()=runBlocking {
        AuthoringFixture().use { api ->
            val headers=listOf("ID","ja-JP","ko-KR","Custom")
            inspect(api,headers)
            val plan=api.sheets.inspect("Japanese Korean",link,"token",GoogleSheets.ALL_HEADERS)
            assertEquals(GoogleSheets.ALL_HEADERS.filter { it !in headers },plan.missing)
            inspect(api,headers);api.json("{}");inspect(api,headers+plan.missing)
            api.sheets.initialize(plan,"token")
            val post=(1..7).map { api.server.takeRequest() }.single { it.method=="POST" }
            val update=Json.parseToJsonElement(post.body.readUtf8()).jsonObject["requests"]!!.jsonArray.single().jsonObject["updateCells"]!!.jsonObject
            assertEquals(4,update["range"]!!.jsonObject["startColumnIndex"]!!.jsonPrimitive.int)
            val appended=update["rows"]!!.jsonArray.single().jsonObject["values"]!!.jsonArray.map { it.jsonObject["userEnteredValue"]!!.jsonObject["stringValue"]!!.jsonPrimitive.content }
            assertEquals(plan.missing,appended);assertFalse("ja-JP" in appended);assertFalse("ko-KR" in appended)
        }
    }
    @Test fun refusesUnrelatedHeadersAndChangedHeaderSnapshotBeforeAnyWrite() = runBlocking {
        AuthoringFixture().use { api ->
            inspect(api,listOf("Name","Budget"))
            try { api.sheets.inspect("",link,"token"); fail("unrelated table accepted") } catch(_: IllegalArgumentException) { }
            inspect(api,emptyList()); val plan=api.sheets.inspect("",link,"token")
            inspect(api,listOf("ID","en-US"))
            try { api.sheets.initialize(plan,"token"); fail("changed headers accepted") } catch(_: IllegalStateException) { }
            repeat(7) { assertEquals("GET",api.server.takeRequest().method) }
        }
    }
    @Test fun emptyFirstRowDoesNotAllowInitializingOtherExistingData() = runBlocking {
        AuthoringFixture().use { api ->
            api.json("""{"sheets":[{"properties":{"sheetId":7,"title":"Existing","gridProperties":{"rowCount":3,"columnCount":26}}}]}""")
            api.json("{}")
            enqueueRows(api,listOf(listOf(listOf("existing budget","42"))))
            try { api.sheets.inspect("",link,"token"); fail("nonempty sheet treated as empty") } catch(_: IllegalArgumentException) { }
            repeat(3) { assertEquals("GET",api.server.takeRequest().method) }
        }
    }
    @Test fun pagingContinuesAcrossEmptyRangesAndHandlesQuotedTitles() = runBlocking {
        AuthoringFixture().use { api ->
            inspect(api,GoogleSheets.HEADERS,rows=1252,title="John's lesson")
            enqueueRows(api,List(5) { emptyList() }); enqueueRows(api,listOf(listOf(values(item("s","late-row")))))
            val file=File.createTempFile("sheet", ".csv")
            try {
                api.sheets.download(Source("s","",link,access="google"),"token",file)
                val csv=CsvReader(file.reader()); val columns=SheetColumns(csv.row()!!)
                assertEquals("late-row",columns.item("s",csv.row()!!).itemId); assertNull(csv.row())
                api.server.takeRequest(); api.server.takeRequest()
                val first=api.server.takeRequest().requestUrl!!
                assertEquals(5,first.queryParameterValues("ranges").size)
                assertTrue(first.queryParameterValues("ranges").first()!!.startsWith("'John''s lesson'!A2:"))
                assertTrue(api.server.takeRequest().requestUrl!!.queryParameterValues("ranges").single()!!.contains("1252"))
            } finally { file.delete() }
        }
    }
    @Test fun rawAppendCannotExecuteFormulasAndRetryDoesNotDuplicateStableIds() = runBlocking {
        AuthoringFixture().use { api ->
            val source=Source("s","",link,access="google"); val item=item("s",en="=IMPORTXML(\"https://example.invalid\")")
            inspect(api,GoogleSheets.HEADERS); enqueueRows(api,listOf(emptyList())); api.json("{}",500)
            try { api.sheets.append(source,listOf(item),"token"); fail("server error ignored") } catch(e: RemoteError) { assertEquals(500,e.status) }
            inspect(api,GoogleSheets.HEADERS); enqueueRows(api,listOf(listOf(values(item))))
            val retry=api.sheets.append(source,listOf(item),"token")
            assertEquals(0,retry.written); assertEquals(1,retry.skipped)
            val requests=(1..7).map { api.server.takeRequest() }
            val post=requests.single { it.method=="POST" }
            assertEquals("RAW",post.requestUrl!!.queryParameter("valueInputOption"))
            assertEquals("INSERT_ROWS",post.requestUrl!!.queryParameter("insertDataOption"))
            assertTrue(post.requestUrl!!.encodedPath.endsWith(":append"))
            assertEquals(item.en,Json.parseToJsonElement(post.body.readUtf8()).jsonObject["values"]!!.jsonArray.single().jsonArray[4].jsonPrimitive.content)
        }
    }
    @Test fun skipsDuplicateContentAndRejectsConflictingIds() = runBlocking {
        AuthoringFixture().use { api ->
            val source=Source("s","",link,access="google"); val pending=item("s")
            inspect(api,GoogleSheets.HEADERS); enqueueRows(api,listOf(listOf(values(pending.copy(itemId="older-id")))))
            assertEquals(1,api.sheets.append(source,listOf(pending),"token").skipped)
            inspect(api,GoogleSheets.HEADERS); enqueueRows(api,listOf(listOf(values(pending.copy(en="Different")))))
            try { api.sheets.append(source,listOf(pending),"token"); fail("ID conflict ignored") } catch(_: IllegalStateException) { }
            repeat(6) { assertEquals("GET",api.server.takeRequest().method) }
        }
    }
    @Test fun successfulAppendRequiresConfirmedRowCount() = runBlocking {
        AuthoringFixture().use { api ->
            val source=Source("s","",link,access="google")
            inspect(api,GoogleSheets.HEADERS); enqueueRows(api,listOf(emptyList())); api.json("{\"updates\":{\"updatedRows\":1}}")
            assertEquals(AppendResult(1,0),api.sheets.append(source,listOf(item("s")),"token"))
        }
    }
    @Test fun sourceInvalidationBeforePostPreventsRemoteWrite() = runBlocking {
        AuthoringFixture().use { api ->
            inspect(api,GoogleSheets.HEADERS); enqueueRows(api,listOf(emptyList()))
            try { api.sheets.append(Source("s","",link,access="google"),listOf(item("s")),"token") { error("source changed") }; fail("stale target written") }
            catch(_: IllegalStateException) { }
            repeat(3) { assertEquals("GET",api.server.takeRequest().method) }
        }
    }
}
