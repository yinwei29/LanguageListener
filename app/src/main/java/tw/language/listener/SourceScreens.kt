@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package tw.language.listener

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.delay
import tw.language.listener.data.*
import tw.language.listener.i18n.UiStrings

@UnstableApi @Composable internal fun SourceLibrary(vm: ListenerViewModel,wide: Boolean) {
    val nav=LocalAppNavigator.current;val sources by vm.sources.collectAsStateWithLifecycle()
    val summaries by vm.sourceSummaries.collectAsStateWithLifecycle()
    var selected by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(sources) { if(sources.none { it.sourceId==selected }) selected=sources.firstOrNull()?.sourceId ?: "" }
    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(24.dp)) {
        Column(if(wide) Modifier.width(320.dp) else Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(14.dp)) {
            PrimaryAction("新增來源或教材") { nav.go("new") }
            MenuLink("所有教材","搜尋所有來源中的五語教材",Icons.Default.Search) { nav.go("browse") }
            if(sources.isEmpty()) EmptyCard("還沒有教材來源","選擇 Excel／CSV、手動新增，或用 AI 建立本機字典。",Icons.Default.Folder)
            for(source in sources) {
                val count=summaries.find { it.sourceId==source.sourceId }?.total ?: 0
                MenuLink(source.name,count.toString()+" · "+tr(if(source.access=="file") "本機 Excel／CSV" else if(source.access=="local" || source.url.isEmpty()) "本機教材" else "已下載的雲端教材"),if(source.access=="file") Icons.Default.Description else Icons.AutoMirrored.Filled.MenuBook,literal=true) { if(wide) selected=source.sourceId else nav.go("source",source.sourceId) }
            }
        }
        if(wide && selected.isNotBlank()) Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(18.dp)) { SourceDetail(selected,vm) }
    }
}

@UnstableApi @Composable internal fun SourceDetail(sourceId: String,vm: ListenerViewModel) {
    val nav=LocalAppNavigator.current;val ui=UiStrings(LocalContext.current)
    val sources by vm.sources.collectAsStateWithLifecycle();val summaries by vm.sourceSummaries.collectAsStateWithLifecycle()
    val source=sources.find { it.sourceId==sourceId }
    var remove by remember { mutableStateOf(false) }
    if(source==null) { EmptyCard("來源已移除","回到教材庫選擇另一個來源。",Icons.Default.Folder);return }
    val stats=summaries.find { it.sourceId==sourceId }
    Panel(source.name) {
        StatusTag(if(source.access=="file") "本機 Excel／CSV" else "教材已存本機")
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
            listOf((stats?.total ?: 0) to "教材",(stats?.learned ?: 0) to "已學會",(stats?.due ?: 0) to "到期複習").forEach { (n,label) -> Column(Modifier.weight(1f)) { Text(n.toString(),style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.Bold);Text(tr(label),style=MaterialTheme.typography.labelMedium) } }
        }
        source.error?.let { Text(ui.message(it),color=MaterialTheme.colorScheme.error) }
        SourceManagement(source,vm)
    }
    MenuLink("查看／編輯教材","在此來源中搜尋、編輯與標記學習狀態",Icons.AutoMirrored.Filled.MenuBook) { nav.go("browse",sourceId) }
    MenuLink("CSV 字典檔","建立、更新或另存到手機",Icons.Default.Description) { nav.go("dictionary",sourceId) }
    MenuLink("離線語音包","安裝後可在飛航模式合成與播放",Icons.AutoMirrored.Filled.VolumeUp) { vm.checkVoices();nav.go("voices") }
    if(source.access=="file") DocumentPanel(vm,{ vm.message.value=it },sourceFilter=sourceId,showImport=false)
    else {
        if(source.sourceId!="demo") MenuLink("連接本機檔案","匯出字典，或將檔案接到這個既有來源",Icons.Default.Folder) { nav.go("files",sourceId) }
        if(source.url.isNotBlank()) {
            Panel(tr("雲端來源")) { Text(ui(R.string.last_sync_0620ac,if(source.lastSync==0L) ui(R.string.not_synced_yet_eeee89) else ui.date(source.lastSync)));Text(source.url,style=MaterialTheme.typography.bodySmall);OutlinedButton(onClick=vm::sync) { Text(ui(R.string.sync_all_sources_84ce63)) } }
        }
    }
    TextButton(onClick={ remove=true }) { Text(ui(R.string.remove_source_0e4e14),color=MaterialTheme.colorScheme.error) }
    if(remove) AlertDialog(onDismissRequest={ remove=false },title={ Text(ui(R.string.remove_source_ffd5c8)) },text={ Text(ui(R.string.lessons_from_will_be_soft_deleted_learning_recor_96f485,source.name)) },confirmButton={ TextButton(onClick={ remove=false;vm.removeSource(sourceId);nav.back() }) { Text(ui(R.string.remove_2f752c)) } },dismissButton={ TextButton(onClick={ remove=false }) { Text(ui(R.string.cancel_4d0b46)) } })
}

@UnstableApi @Composable internal fun NewSourceScreen(vm: ListenerViewModel) {
    val nav=LocalAppNavigator.current
    MenuLink("用 AI 建立字典","ChatGPT／Gemini 產生、審核並保存 CSV",Icons.Default.AutoAwesome) { nav.go("ai") }
    MenuLink("選擇 Excel／CSV","從手機選取教材檔案並初始化",Icons.Default.Folder) { nav.go("files") }
    MenuLink("建立空白 CSV","建立標準欄位，再離線新增教材",Icons.Default.Add) { nav.go("files") }
    LocalLessonsPanel(vm)
    MenuLink("連接 Google Sheets","初始化可寫來源，或加入唯讀來源",Icons.Default.Cloud) { nav.go("google") }
    Panel(tr("本機先開始")) { Text(tr("不使用 AI 或 Google，也能新增教材與離線學習。")) }
}

@UnstableApi @Composable internal fun PublicSourcePanel(vm: ListenerViewModel) {
    val ui=UiStrings(LocalContext.current);var name by rememberSaveable { mutableStateOf("") };var url by rememberSaveable { mutableStateOf("") }
    Panel(tr("公開唯讀來源")) {
        OutlinedTextField(name,{ name=it },label={ Text(ui.message("來源名稱")) },modifier=Modifier.fillMaxWidth())
        OutlinedTextField(url,{ url=it },label={ Text("Google Sheets URL") },modifier=Modifier.fillMaxWidth())
        PrimaryAction("新增公開唯讀來源",url.isNotBlank()) { vm.addSource(name,url) }
    }
}

@UnstableApi @Composable internal fun LessonBrowser(sourceId: String,vm: ListenerViewModel,browser: LibraryBrowser=viewModel()) {
    val ui=UiStrings(LocalContext.current);val sources by vm.sources.collectAsStateWithLifecycle()
    val items by browser.items.collectAsStateWithLifecycle();val more by browser.more.collectAsStateWithLifecycle()
    val busy by browser.busy.collectAsStateWithLifecycle();val error by browser.error.collectAsStateWithLifecycle()
    val categories by vm.categories.collectAsStateWithLifecycle()
    var query by rememberSaveable(sourceId) { mutableStateOf("") };var category by rememberSaveable(sourceId) { mutableStateOf("") }
    var editingSource by rememberSaveable { mutableStateOf("") }
    var editingId by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(query,category,sourceId) { delay(200);browser.load(sourceId,query,category) }
    Panel(tr("搜尋本機教材")) {
        OutlinedTextField(query,{ query=it },label={ Text(tr("搜尋五種語言文字")) },singleLine=true,modifier=Modifier.fillMaxWidth())
        Choice(ui(R.string.category_7c6fd7),category,listOf("" to ui(R.string.all_778fc8))+categories.map { it.category }.distinct().map { it to it }) { category=it }
    }
    if(busy) LinearProgressIndicator(Modifier.fillMaxWidth())
    error?.let { Text(ui.message(it),color=MaterialTheme.colorScheme.error) }
    if(!busy && items.isEmpty()) EmptyCard("沒有符合條件的教材","調整搜尋文字或分類，或先新增教材。",Icons.Default.Search)
    for(item in items.filter { sourceId.isEmpty() || it.sourceId==sourceId }) Panel(item.category) {
        PlaybackOptions.LANGUAGES.filter { item.text(it).isNotBlank() }.forEach { Text(item.text(it),style=MaterialTheme.typography.titleMedium) }
        Text(item.type+" · "+item.level,style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
        Actions {
            if(item.sourceId==Repository.LOCAL_SOURCE_ID || sources.any { it.sourceId==item.sourceId && it.access in listOf("local","file") }) TextButton(onClick={ editingSource=item.sourceId;editingId=item.itemId }) { Text(ui.message("編輯本機教材")) }
            TextButton(onClick={ vm.markLibrary(item,true) }) { Text(ui(R.string.learned_0f130c)) }
            TextButton(onClick={ vm.markLibrary(item,false) }) { Text(ui(R.string.reset_to_unlearned_6ddb59)) }
        }
    }
    if(more) OutlinedButton(enabled=!busy,onClick={ browser.load(sourceId,query,category,true) }) { Text(ui(R.string.load_next_100_entries_a1e2e1)) }
    items.find { it.sourceId==editingSource && it.itemId==editingId }?.let { original -> LocalLessonDialog(original,vm) { editingId="";browser.load(sourceId,query,category) } }
}

@UnstableApi @Composable internal fun QueueScreen(vm: ListenerViewModel) {
    val nav=LocalAppNavigator.current
    val saved by vm.session.collectAsStateWithLifecycle();val current by vm.index.collectAsStateWithLifecycle()
    var page by rememberSaveable(saved?.sessionId) { mutableIntStateOf(1) }
    val entries by produceState<List<Pair<QueueEntry,Item?>>>(emptyList(),saved?.sessionId,page) {
        value=vm.repo.dao.queuePage(100*page,0).map { it to vm.repo.dao.item(it.sourceId,it.itemId) }
    }
    for((offset,pair) in entries.withIndex()) {
        val (entry,item)=pair
        MenuLink((offset+1).toString()+" · "+(item?.text(saved?.options?.native ?: "zh-TW") ?: entry.itemId),item?.text(saved?.options?.target ?: "en-US") ?: "",if(offset==current) Icons.AutoMirrored.Filled.VolumeUp else Icons.Default.PlayArrow,literal=true) { saved?.let { vm.jumpTo(offset,it.sessionId);nav.back() } }
    }
    if(entries.size==page*100) OutlinedButton(onClick={ page++ }) { Text(tr("載入下一頁")) }
}
