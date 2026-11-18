package tw.language.listener

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import tw.language.listener.authoring.*
import tw.language.listener.data.*

class DocumentCloudTest {
    private val source=Source("file","lessons","https://docs.google.com/spreadsheets/d/example/edit#gid=7",access="file")
    private val headers=GoogleSheets.HEADERS+"Custom"
    private fun snapshot(api: AuthoringFixture,items: List<Item>) {
        api.json("""{"sheets":[{"properties":{"sheetId":7,"title":"Lessons","gridProperties":{"rowCount":${maxOf(3,items.size+1)},"columnCount":26}}}]}""")
        api.json(buildJsonObject { put("values",JsonArray(listOf(JsonArray(headers.map(::JsonPrimitive))))) }.toString())
        api.json(buildJsonObject { putJsonArray("valueRanges") { add(buildJsonObject { put("values",JsonArray(items.map { item -> JsonArray(headers.map { JsonPrimitive(if(it=="Custom") "keep formula" else GoogleSheets.cell(item,it)) }) })) }) } }.toString())
    }
    @Test fun cloudSyncUpdatesStableIdsAsRawStringsAndDoesNotTouchUnknownColumns()=runBlocking {
        AuthoringFixture().use { api ->
            val item=Item("file","stable",zh="一",en="one",id="satu")
            snapshot(api,listOf(item));val initial=api.sheets.snapshot(source,"token")
            val edited=item.copy(en="=1+1")
            snapshot(api,listOf(item));api.json("{}");snapshot(api,listOf(edited))
            api.sheets.writeMerged(source,initial,listOf(edited),"token")
            val requests=(1..10).map { api.server.takeRequest() }
            val post=requests.single { it.method=="POST" }
            val updates=Json.parseToJsonElement(post.body.readUtf8()).jsonObject["requests"]!!.jsonArray
            assertEquals(13,updates.size)
            updates.forEach { request ->
                val update=request.jsonObject["updateCells"]!!.jsonObject
                assertTrue(update["range"]!!.jsonObject["startColumnIndex"]!!.jsonPrimitive.int<13)
                assertEquals("userEnteredValue",update["fields"]!!.jsonPrimitive.content)
                val value=update["rows"]!!.jsonArray.single().jsonObject["values"]!!.jsonArray.single().jsonObject["userEnteredValue"]!!.jsonObject
                assertTrue(value.containsKey("stringValue"));assertFalse(value.containsKey("formulaValue"))
            }
        }
    }
    @Test fun changedCloudSnapshotStopsBeforeAnyPost()=runBlocking {
        AuthoringFixture().use { api ->
            val item=Item("file","stable",zh="一",en="one",id="satu")
            snapshot(api,listOf(item));val initial=api.sheets.snapshot(source,"token")
            snapshot(api,listOf(item.copy(en="another device")))
            try { api.sheets.writeMerged(source,initial,listOf(item.copy(en="app")),"token");fail("overwrote concurrent edit") } catch(_: IllegalStateException) { }
            (1..6).forEach { assertEquals("GET",api.server.takeRequest().method) }
        }
    }
    @Test fun manyUpdatesAreBatchedWithStableRowsAndVerifiedAfterwards()=runBlocking {
        AuthoringFixture().use { api ->
            val old=(1..12).map { Item("file","id-$it",zh="一",en="one",id="satu") }
            snapshot(api,old);val initial=api.sheets.snapshot(source,"token")
            val updated=old.map { it.copy(en="updated") }
            snapshot(api,old);api.json("{}");api.json("{}");snapshot(api,updated)
            api.sheets.writeMerged(source,initial,updated,"token")
            val posts=(1..11).map { api.server.takeRequest() }.filter { it.method=="POST" }
            assertEquals(2,posts.size)
            val requests=posts.flatMap { Json.parseToJsonElement(it.body.readUtf8()).jsonObject["requests"]!!.jsonArray.also { batch -> assertTrue(batch.size<=100) } }
            assertEquals(156,requests.size)
            assertTrue(requests.all { it.jsonObject["updateCells"]!!.jsonObject["range"]!!.jsonObject["startRowIndex"]!!.jsonPrimitive.int in 1..12 })
        }
    }

}
