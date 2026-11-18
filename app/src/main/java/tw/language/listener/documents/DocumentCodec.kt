package tw.language.listener.documents

import org.w3c.dom.*
import tw.language.listener.authoring.GoogleSheets
import tw.language.listener.data.*
import tw.language.listener.sync.*
import java.io.*
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.*
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult

/** Bounded CSV/XLSX editing; unrelated workbook parts and unknown columns are preserved. */
class DocumentTable(val headers: MutableList<String>,val rows: MutableList<MutableList<String>>,
    val sheets: Map<String,String> = emptyMap(),val sheet: String="",private val parts: LinkedHashMap<String,ByteArray>?=null,
    private val xml: Document?=null) {
    private val formulaCells: Set<String> = xml?.getElementsByTagNameNS("*","c")?.let { cells ->
        (0 until cells.length).map { cells.item(it) as Element }.filter { it.getElementsByTagNameNS("*","f").length>0 }.map { it.getAttribute("r") }.toSet()
    } ?: emptySet()
    val missing get()=GoogleSheets.HEADERS.filter { required -> headers.none { it.equals(required,true) } }
    fun initialize(): Int {
        require(headers.size<=100 && headers.none(String::isBlank) && headers.map { it.lowercase() }.distinct().size==headers.size) { "檔案欄名不可空白、重複或超過 100 欄" }
        require(headers.isEmpty() || headers.any { h -> listOf("ID","zh-TW","en-US","id-ID").any { it.equals(h,true) } }) { "檔案不是教材格式，請建立新的 CSV，避免覆寫原檔" }
        require(headers.isNotEmpty() || rows.all { row -> row.all(String::isBlank) }) { "第一列空白但下方有資料，未初始化" }
        headers+=missing
        require(headers.size<=100)
        val idColumn=headers.indexOfFirst { it.equals("ID",true) }
        val languageColumns=headers.indices.filter { headers[it].lowercase() in listOf("zh-tw","en-us","id-id") }
        var generated=0
        rows.forEach { row ->
            require(row.size<=headers.size) { "檔案資料欄位超出標題，未寫入" }
            while(row.size<headers.size) row.add("")
            if(languageColumns.any { row[it].isNotBlank() } && row[idColumn].isBlank()) { row[idColumn]=UUID.randomUUID().toString(); generated++ }
        }
        return generated
    }
    fun items(source: String): List<Item> {
        val columns=SheetColumns(headers)
        val languageColumns=headers.indices.filter { headers[it].lowercase() in listOf("zh-tw","en-us","id-id") }
        return rows.filter { row -> languageColumns.any { row.getOrElse(it) { "" }.isNotBlank() } }.map { columns.item(source,it+List(headers.size-it.size) { "" }) }.also {
            require(it.map(Item::itemId).distinct().size==it.size) { "檔案含重複教材 ID，請先修正再同步" }
        }
    }
    fun update(items: List<Item>) {
        val idColumn=headers.indexOfFirst { it.equals("ID",true) }
        val indexed=rows.withIndex().filter { it.value.getOrElse(idColumn) { "" }.isNotBlank() }.associate { it.value[idColumn] to it.index }
        for(item in items) {
            val rowIndex=indexed[item.itemId] ?: rows.size
            if(rowIndex==rows.size) rows.add(MutableList(headers.size) { "" })
            val row=rows[rowIndex]; while(row.size<headers.size) row.add("")
            headers.forEachIndexed { col,header -> if(GoogleSheets.HEADERS.any { it.equals(header,true) }) {
                val value=GoogleSheets.cell(item,header)
                if(row[col]!=value) {
                    if(xml!=null) {
                        val address=GoogleSheets.columnName(col+1)+(rowIndex+2)
                        require(address !in formulaCells) { "教材欄位含 Excel 公式，請另存純值檔案後再同步" }
                    }
                    row[col]=value
                }
            } }
        }
    }
    fun encode(): ByteArray {
        if(parts==null || xml==null) return ("\uFEFF"+(listOf(headers)+rows).joinToString("\r\n",postfix="\r\n") { GoogleSheets.csv(it) }).toByteArray(Charsets.UTF_8)
        val strings=DocumentCodec.shared(parts)
        val ns=xml.documentElement.namespaceURI
        val data=xml.getElementsByTagNameNS("*","sheetData").item(0) as? Element ?: error("Excel 缺少 sheetData")
        val existingRows=(0 until data.childNodes.length).mapNotNull { data.childNodes.item(it) as? Element }.filter { it.localName=="row" }.associateBy { it.getAttribute("r").toInt() }.toMutableMap()
        (listOf(headers)+rows).forEachIndexed { index,values ->
            val row=existingRows[index+1] ?: xml.createElementNS(ns,"row").apply {
                setAttribute("r",(index+1).toString())
                val next=existingRows.keys.filter { it>index+1 }.minOrNull()?.let { existingRows[it] }
                data.insertBefore(this,next); existingRows[index+1]=this
            }
            val cells=(0 until row.childNodes.length).mapNotNull { row.childNodes.item(it) as? Element }.filter { it.localName=="c" }.associateBy { it.getAttribute("r") }.toMutableMap()
            values.forEachIndexed { col,value ->
                val address=GoogleSheets.columnName(col+1)+(index+1)
                val old=cells[address]
                // Preserve existing formulas/styles and untouched representations.
                if(old!=null && DocumentCodec.cellText(old,strings)==value) return@forEachIndexed
                require(old==null || old.getElementsByTagNameNS("*","f").length==0) { "教材欄位含 Excel 公式，請另存純值檔案後再同步" }
                val cell=old ?: xml.createElementNS(ns,"c").apply {
                    setAttribute("r",address)
                    val next=cells.values.filter { DocumentCodec.column(it.getAttribute("r"))>col }.minByOrNull { DocumentCodec.column(it.getAttribute("r")) }
                    row.insertBefore(this,next); cells[address]=this
                }
                while(cell.hasChildNodes()) cell.removeChild(cell.firstChild)
                cell.setAttribute("t","inlineStr")
                val text=xml.createElementNS(ns,"t").apply { setAttributeNS("http://www.w3.org/XML/1998/namespace","xml:space","preserve"); textContent=value }
                cell.appendChild(xml.createElementNS(ns,"is").apply { appendChild(text) })
            }
        }
        (xml.getElementsByTagNameNS("*","dimension").item(0) as? Element)?.setAttribute("ref","A1:${GoogleSheets.columnName(headers.size)}${rows.size+1}")
        val encoded=ByteArrayOutputStream(); TransformerFactory.newInstance().newTransformer().transform(DOMSource(xml),StreamResult(encoded))
        require(encoded.size().toLong()+parts.filterKeys { it!=sheet }.values.sumOf { it.size.toLong() }<=64L*1024*1024) { "Excel 展開內容超過 64 MiB 安全上限" }
        val output=ByteArrayOutputStream()
        ZipOutputStream(output).use { zip -> parts.forEach { (name,bytes) -> zip.putNextEntry(ZipEntry(name)); zip.write(if(name==sheet) encoded.toByteArray() else bytes); zip.closeEntry() } }
        return output.toByteArray()
    }
}
object DocumentCodec {
    const val MAX_BYTES=32*1024*1024
    fun hash(bytes: ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    fun read(input: InputStream): ByteArray {
        val out=ByteArrayOutputStream();val buffer=ByteArray(8192)
        while(true) { val n=input.read(buffer);if(n<0) break;require(out.size()+n<=MAX_BYTES) { "檔案超過 32 MiB 安全上限" };out.write(buffer,0,n) };return out.toByteArray()
    }
    internal fun dom(bytes: ByteArray): Document {
        val text=bytes.toString(Charsets.UTF_8)
        require(!text.contains('\u0000') && !text.contains("<!DOCTYPE",true) && !text.contains("<!ENTITY",true)) { "Excel XML 含不支援的實體或編碼" }
        val factory=DocumentBuilderFactory.newInstance().apply { isNamespaceAware=true;isExpandEntityReferences=false }
        return factory.newDocumentBuilder().apply { setEntityResolver { _,_->throw IllegalArgumentException("不允許外部 XML 實體") } }.parse(bytes.inputStream())
    }
    internal fun column(address: String): Int=address.takeWhile(Char::isLetter).fold(0) { n,c -> n*26+(c.uppercaseChar()-'A'+1) }-1
    internal fun shared(parts: Map<String,ByteArray>): List<String> = parts["xl/sharedStrings.xml"]?.let { bytes ->
        val nodes=dom(bytes).getElementsByTagNameNS("*","si");(0 until nodes.length).map { node ->
            val texts=(nodes.item(node) as Element).getElementsByTagNameNS("*","t");(0 until texts.length).joinToString("") { texts.item(it).textContent }
        }
    } ?: emptyList()
    internal fun cellText(cell: Element,strings: List<String>): String {
        val v=cell.getElementsByTagNameNS("*","v").item(0)?.textContent.orEmpty()
        return when(cell.getAttribute("t")) { "s"->strings.getOrNull(v.toIntOrNull() ?: -1) ?: error("Excel 共用字串索引不合法");"inlineStr"->{ val texts=cell.getElementsByTagNameNS("*","t");(0 until texts.length).joinToString("") { texts.item(it).textContent } };else->v }
    }
    fun decode(bytes: ByteArray,selectedSheet: String=""): DocumentTable {
        require(bytes.size<=MAX_BYTES)
        if(bytes.take(2)!=listOf(0x50.toByte(),0x4b.toByte())) {
            require(bytes.none { it==0.toByte() }) { "請選擇 UTF-8 CSV 或 .xlsx，舊版 .xls 請先轉存" }
            val text=try { Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes)).toString() }
                catch(_: java.nio.charset.CharacterCodingException) { throw IllegalArgumentException("請選擇 UTF-8 CSV 或 .xlsx，舊版 .xls 請先轉存") }
            val csv=CsvReader(text.removePrefix("\uFEFF").reader())
            val headers=(csv.row() ?: emptyList()).map { it.trim() }.toMutableList()
            val rows=mutableListOf<MutableList<String>>()
            while(true) { val row=csv.row() ?: break;rows+=row.toMutableList() }
            return DocumentTable(if(headers.all(String::isBlank)) mutableListOf() else headers,rows)
        }
        val parts=linkedMapOf<String,ByteArray>();var total=0;var entries=0
        ZipInputStream(bytes.inputStream()).use { zip -> while(true) {
            val entry=zip.nextEntry ?: break
            require(++entries<=10000) { "Excel ZIP 路徑或項目不合法" }
            require(!entry.name.startsWith('/') && '\\' !in entry.name && entry.name.split('/').none { it==".." } && entry.name !in parts && parts.size<10000) { "Excel ZIP 路徑或項目不合法" }
            if(!entry.isDirectory) { val data=read(zip);total+=data.size;require(total<=64*1024*1024) { "Excel 展開內容超過 64 MiB 安全上限" };parts[entry.name]=data }
        } }
        require(parts.keys.none { it.contains("vbaProject",true) || it.startsWith("xl/externalLinks/") }) { "請使用不含巨集與外部連結的 .xlsx" }
        val rel=dom(checkNotNull(parts["xl/_rels/workbook.xml.rels"])).getElementsByTagNameNS("*","Relationship")
        val paths=(0 until rel.length).associate { val e=rel.item(it) as Element;require(e.getAttribute("TargetMode")!="External");e.getAttribute("Id") to e.getAttribute("Target").let { target -> if(target.startsWith('/')) target.removePrefix("/") else "xl/$target" } }
        val nodes=dom(checkNotNull(parts["xl/workbook.xml"])).getElementsByTagNameNS("*","sheet")
        val sheets=(0 until nodes.length).associate { val e=nodes.item(it) as Element;e.getAttribute("name") to checkNotNull(paths[e.getAttributeNS("http://schemas.openxmlformats.org/officeDocument/2006/relationships","id")]) }
        val path=if(selectedSheet.startsWith("name:")) sheets[selectedSheet.removePrefix("name:")] else selectedSheet.ifEmpty { sheets.values.firstOrNull() ?: error("Excel 沒有工作表") };require(path!=null && path in sheets.values) { "請先重新選擇本機檔案；備份不移轉檔案存取權限" }
        val doc=dom(checkNotNull(parts[path]));val strings=shared(parts);val rowNodes=doc.getElementsByTagNameNS("*","row")
        val grid=mutableListOf<MutableList<String>>()
        for(i in 0 until rowNodes.length) {
            val e=rowNodes.item(i) as Element;val number=e.getAttribute("r").toInt()
            require(number in 1..1_048_576 && number.toLong()*maxOf(1,grid.firstOrNull()?.size ?: 1)<=8_000_000L) { "Excel 工作表範圍過大" }
            while(grid.size<number) grid+=mutableListOf<String>()
            val cells=e.getElementsByTagNameNS("*","c")
            for(c in 0 until cells.length) { val cell=cells.item(c) as Element;val col=column(cell.getAttribute("r"));require(col in 0..99) { "教材工作表最多支援 100 欄" };while(grid[number-1].size<=col) grid[number-1]+="";grid[number-1][col]=cellText(cell,strings) }
        }
        val header=grid.firstOrNull()?.map { it.trim() }?.toMutableList() ?: mutableListOf()
        return DocumentTable(if(header.all(String::isBlank)) mutableListOf() else header,grid.drop(1).toMutableList(),sheets,path,parts,doc)
    }
}
