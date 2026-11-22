package tw.language.listener

import org.junit.Assert.*
import org.junit.Test
import tw.language.listener.documents.*
import tw.language.listener.data.*
import java.io.*
import java.util.zip.*

class DocumentCodecTest {
    private fun zip(parts: Map<String,String>): ByteArray {
        val out=ByteArrayOutputStream();ZipOutputStream(out).use { z -> parts.forEach { (p,v) -> z.putNextEntry(ZipEntry(p));z.write(v.toByteArray());z.closeEntry() } };return out.toByteArray()
    }
    private fun parts(bytes: ByteArray): Map<String,ByteArray> {
        val out=linkedMapOf<String,ByteArray>();ZipInputStream(bytes.inputStream()).use { z -> while(true) { val e=z.nextEntry ?: break;out[e.name]=z.readBytes() } };return out
    }
    private fun workbook(sheet: String)=zip(linkedMapOf(
        "xl/workbook.xml" to """<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets><sheet name="Lessons" sheetId="1" r:id="r1"/><sheet name="Other" sheetId="2" r:id="r2"/></sheets></workbook>""",
        "xl/_rels/workbook.xml.rels" to """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="r1" Target="worksheets/sheet1.xml"/><Relationship Id="r2" Target="worksheets/sheet2.xml"/></Relationships>""",
        "xl/worksheets/sheet1.xml" to sheet,"xl/worksheets/sheet2.xml" to "<untouched>private worksheet</untouched>","xl/styles.xml" to "<styles>preserved</styles>"
    ))
    @Test fun csvInitializesMissingColumnsAndStableIdsWithoutLosingUnknownCells() {
        val table=DocumentCodec.decode("zh-TW,en-US,Custom\r\n你好,Hello,keep\r\n".toByteArray())
        assertEquals(1,table.initialize());val item=table.items("file").single();assertTrue(item.itemId.isNotBlank())
        table.update(listOf(item.copy(en="Hello,\nworld")))
        val roundTrip=DocumentCodec.decode(table.encode());assertEquals(0,roundTrip.initialize())
        assertEquals(item.itemId,roundTrip.items("file").single().itemId)
        assertEquals("Hello,\nworld",roundTrip.items("file").single().en)
        assertEquals("keep",roundTrip.rows.single()[roundTrip.headers.indexOf("Custom")])
    }
    @Test fun blankCsvCanBeCreatedOfflineAndImportedInExcelCompatibleUtf8() {
        val table=DocumentCodec.decode(byteArrayOf());assertEquals(0,table.initialize())
        table.update(listOf(Item("file","new",zh="水",en="Water",id="Air")))
        val bytes=table.encode();assertTrue(bytes.toString(Charsets.UTF_8).startsWith("\uFEFF"))
        assertEquals("new",DocumentCodec.decode(bytes).items("file").single().itemId)
    }
    @Test fun initializationRejectsUnrelatedTablesBlankHeaderDataAndDuplicateIds() {
        for(csv in listOf("Name,Amount\nAlice,30","\nhello,world","ID,zh-TW,en-US,id-ID\na,一,one,satu\na,二,two,dua")) {
            assertThrows(IllegalArgumentException::class.java) { val table=DocumentCodec.decode(csv.toByteArray());table.initialize();table.items("s") }
        }
    }
    @Test fun xlsxEditsOnlyChosenSheetPreservingUnknownFormulasStylesAndOtherParts() {
        val xml="""<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData><row r="1"><c r="A1" t="inlineStr"><is><t>ID</t></is></c><c r="B1" t="inlineStr"><is><t>zh-TW</t></is></c><c r="C1" t="inlineStr"><is><t>en-US</t></is></c><c r="D1" t="inlineStr"><is><t>Custom</t></is></c></row><row r="2"><c r="A2" t="inlineStr"><is><t>stable</t></is></c><c r="B2" t="inlineStr"><is><t>你好</t></is></c><c r="C2" t="inlineStr"><is><t>Hello</t></is></c><c r="D2" s="3"><f>1+1</f><v>2</v></c></row></sheetData></worksheet>"""
        val bytes=workbook(xml);val table=DocumentCodec.decode(bytes,"name:Lessons");
        assertThrows(IllegalArgumentException::class.java) { DocumentCodec.decode(bytes,"name:Renamed") };assertEquals(2,table.sheets.size);table.initialize()
        table.update(listOf(table.items("s").single().copy(en="Edited",ja="こんにちは",ko="안녕하세요")))
        val encoded=table.encode();val old=parts(bytes);val fresh=parts(encoded)
        assertArrayEquals(old["xl/worksheets/sheet2.xml"],fresh["xl/worksheets/sheet2.xml"])
        assertArrayEquals(old["xl/styles.xml"],fresh["xl/styles.xml"])
        assertTrue(fresh.getValue("xl/worksheets/sheet1.xml").toString(Charsets.UTF_8).contains("<f>1+1</f>"))
        val restored=DocumentCodec.decode(encoded).items("s").single()
        assertEquals("Edited",restored.en);assertEquals("こんにちは",restored.ja);assertEquals("안녕하세요",restored.ko)
    }
    @Test fun maliciousArchivesAndExternalEntitiesAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { DocumentCodec.decode(zip(mapOf("../escape" to "bad"))) }
        val xml="""<!DOCTYPE worksheet [<!ENTITY secret SYSTEM "file:///etc/passwd">]><worksheet><sheetData>&secret;</sheetData></worksheet>"""
        assertThrows(IllegalArgumentException::class.java) { DocumentCodec.decode(workbook(xml)) }
        assertThrows(IllegalArgumentException::class.java) { DocumentCodec.decode(ByteArray(4) { 0 }) }
        assertThrows(IllegalArgumentException::class.java) { DocumentCodec.decode(byteArrayOf(0x81.toByte(),0x81.toByte())) }
    }
    @Test fun mergeCombinesIndependentEditsPreservesAbsentRowsAndRejectsConflicts() {
        val a=Item("s","a",zh="一",en="one",id="satu");val b=a.copy(itemId="b")
        val merged=DocumentMerge.merge(listOf(a,b),listOf(a.copy(en="app"),b),listOf(a,b.copy(en="file")))
        assertEquals(listOf("app","file"),merged.map { it.en })
        assertEquals(listOf(a),DocumentMerge.merge(listOf(a),listOf(a),emptyList()))
        assertThrows(IllegalStateException::class.java) { DocumentMerge.merge(listOf(a),listOf(a.copy(en="app")),listOf(a.copy(en="file"))) }
        assertEquals(listOf(a),DocumentMerge.merge(emptyList(),listOf(a),listOf(a)))
    }
}
