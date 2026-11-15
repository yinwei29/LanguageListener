package tw.language.listener

import org.junit.Assert.*
import org.junit.Test
import tw.language.listener.sync.*

class CsvTest {
    @Test fun quotesMultilineBomAndTrailingEmptyField() {
        val csv=CsvReader("\uFEFFID,zh-TW,en-US,id-ID,tags\r\n1,\"你好，\n朋友\",\"Hello \"\"friend\"\"\",Halo,\r\n".reader())
        val columns=SheetColumns(csv.row()!!)
        val item=columns.item("source",csv.row()!!)
        assertEquals("你好，\n朋友",item.zh)
        assertEquals("Hello \"friend\"",item.en)
        assertEquals("",item.tags)
        assertNull(csv.row())
    }
    @Test fun normalizedSheetAndPublishedUrls() {
        assertEquals("https://docs.google.com/spreadsheets/d/abc-123/export?format=csv&gid=42",
            SheetUrl.normalize("https://docs.google.com/spreadsheets/d/abc-123/edit#gid=42"))
        assertEquals("https://docs.google.com/spreadsheets/d/e/abc/pub?output=csv&gid=5",
            SheetUrl.normalize("https://docs.google.com/spreadsheets/d/e/abc/pub?gid=5&output=csv"))
    }
    @Test fun rejectsInvalidCsvAndSource() {
        listOf("\"not closed", "a\"b,c", "\"a\"x,b").forEach { text ->
            assertThrows(IllegalArgumentException::class.java) { CsvReader(text.reader()).row() }
        }
        listOf("http://docs.google.com/spreadsheets/d/a", "https://evil.example/spreadsheets/d/a",
            "https://user:password@docs.google.com/spreadsheets/d/a", "https://docs.google.com:444/spreadsheets/d/a").forEach {
            assertThrows(IllegalArgumentException::class.java) { SheetUrl.normalize(it) }
        }
        assertThrows(IllegalArgumentException::class.java) { SheetColumns(listOf("zh-TW","en-US","id-ID")) }
    }
    @Test fun explicitDisabledAndDeletionFlags() {
        val columns=SheetColumns(listOf("ID","zh-TW","en-US","id-ID","enabled","status"))
        val item=columns.item("s",listOf("stable","你好","Hello","Halo","false","deleted"))
        assertFalse(item.enabled); assertTrue(item.deleted)
        assertThrows(IllegalArgumentException::class.java) { columns.item("s",listOf("","你好","Hello","Halo","true","")) }
    }
}
