@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package tw.language.listener

import android.Manifest
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import android.speech.tts.TextToSpeech
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import tw.language.listener.data.*
import java.text.DateFormat
import java.util.Date

@UnstableApi
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { ListenerScreen() }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@UnstableApi
@Composable
fun ListenerScreen(vm: ListenerViewModel=viewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val tab by vm.tab.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val preview by vm.preview.collectAsStateWithLifecycle()
    val snack=remember { SnackbarHostState() }
    val context=LocalContext.current
    val notification=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) { if(Build.VERSION.SDK_INT>=33) notification.launch(Manifest.permission.POST_NOTIFICATIONS) }
    LaunchedEffect(message) { message?.let { snack.showSnackbar(it); vm.message.value=null } }
    val colors=if(settings.dark) darkColorScheme(primary=Color(0xFF8BC1FF)) else lightColorScheme(
        primary=Color(0xFF397FC9),onBackground=Color(0xFF172A43),background=Color.White,primaryContainer=Color(0xFFE8F3FF))
    MaterialTheme(colorScheme=colors) {
        BackHandler(tab!=0) { vm.chooseTab(0) }
        Scaffold(topBar={ TopAppBar(title={ Text("Language Listener") }) },
            snackbarHost={ SnackbarHost(snack) },
            bottomBar={ NavigationBar {
                listOf("首頁","播放器","教材庫","設定").forEachIndexed { index,label ->
                    NavigationBarItem(selected=tab==index,onClick={ vm.chooseTab(index) },label={ Text(label) },icon={
                        Icon(when(index) { 0->Icons.Default.Home; 1->Icons.Default.Headphones; 2->Icons.Default.LibraryBooks; else->Icons.Default.Settings },contentDescription=null)
                    })
                }
            } }) { padding ->
            Column(Modifier.padding(padding).fillMaxSize()) {
                if(busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
                    NetworkStatus()
                    when(tab) {
                        0->Home(vm)
                        1->PlayerScreen(vm)
                        2->LibraryScreen(vm)
                        else->SettingsScreen(vm)
                    }
                    Spacer(Modifier.height(12.dp))
                }
            }
        }
        preview?.let { pending ->
            AlertDialog(onDismissRequest={ if(!busy) vm.dismissPreview() },title={ Text("匯入預覽") },text={
                Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    Text("日期：${date(pending.manifest.createdAt)}\n版本：${pending.manifest.appVersion}／schema ${pending.manifest.schemaVersion}\n教材：${pending.manifest.itemCount} 筆\n學習紀錄：${pending.manifest.progressCount} 筆\n來源：${pending.manifest.sourceCount}")
                    pending.sources.forEach { Text(it.name) }
                    Text("${if(pending.manifest.full) "完整" else "僅學習紀錄"}備份。完整覆蓋會刪除目前資料；智慧合併依修改時間選取較新進度，保留本機教材與非空佇列。")
                    if(!pending.manifest.full) Text("輕量備份不含教材文字；覆蓋後需連線同步來源，才能離線播放。")
                }
            },confirmButton={ TextButton(enabled=!busy,onClick={ vm.restore(false) }) { Text("智慧合併") } },dismissButton={
                Column {
                    TextButton(enabled=!busy,onClick={ vm.restore(true) }) { Text("完整覆蓋") }
                    TextButton(enabled=!busy,onClick=vm::dismissPreview) { Text("取消") }
                }
            })
        }
    }
}

@Composable
private fun NetworkStatus() {
    val context=LocalContext.current
    val cm=remember { context.getSystemService(ConnectivityManager::class.java) }
    fun connected()=cm.getNetworkCapabilities(cm.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)==true
    var online by remember { mutableStateOf(connected()) }
    DisposableEffect(cm) {
        val callback=object: ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { online=connected() }
            override fun onLost(network: Network) { online=connected() }
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) { online=connected() }
        }
        cm.registerDefaultNetworkCallback(callback)
        onDispose { cm.unregisterNetworkCallback(callback) }
    }
    Text(if(online) "已連線 · 教材播放使用本機離線語音" else "目前離線 · 可播放已下載教材",style=MaterialTheme.typography.labelLarge)
}

@Composable
private fun Panel(title: String, content: @Composable ColumnScope.()->Unit) {
    Card(Modifier.fillMaxWidth(),colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.primaryContainer)) {
        Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Text(title,style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold)
            content()
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Actions(content: @Composable FlowRowScope.()->Unit) {
    FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp),verticalArrangement=Arrangement.spacedBy(4.dp),content=content)
}

@UnstableApi @Composable
private fun Home(vm: ListenerViewModel) {
    val stats by vm.stats.collectAsStateWithLifecycle()
    val streak by vm.streak.collectAsStateWithLifecycle()
    val saved by vm.session.collectAsStateWithLifecycle()
    val total by vm.queueSize.collectAsStateWithLifecycle()
    val current by vm.item.collectAsStateWithLifecycle()
    Panel("每天聽一點，語言更自然。") {
        Text("教材 ${stats.total} 筆　已學會 ${stats.learned} 筆\n收藏 ${stats.favorite} 筆　連續學習 $streak 天")
    }
    Panel("繼續學習") {
        Text("${current?.category ?: "尚無場次"} · ${if(total>0) (saved?.position ?: 0)+1 else 0}/$total 筆")
        LinearProgressIndicator(progress={ if(total==0) 0f else ((saved?.position ?: 0)+1f)/total },modifier=Modifier.fillMaxWidth())
        Button(onClick={ vm.chooseTab(1) }) { Text("開啟播放器") }
    }
    Panel("快速開始") {
        Actions {
            Button(onClick={ vm.start(vm.settings.value.playback.copy(type="word")) }) { Text("單字練習") }
            Button(onClick={ vm.start(vm.settings.value.playback.copy(type="sentence")) }) { Text("句子聽力") }
            Button(onClick={ vm.start(vm.settings.value.playback.copy(random=true)) }) { Text("亂數學習") }
            Button(onClick={ vm.start(vm.settings.value.playback.copy(state="learned",excludeLearned=false)) }) { Text("複習已學") }
        }
    }
}

@UnstableApi @Composable
private fun PlayerScreen(vm: ListenerViewModel) {
    val item by vm.item.collectAsStateWithLifecycle()
    val progress by vm.progress.collectAsStateWithLifecycle()
    val index by vm.index.collectAsStateWithLifecycle()
    val size by vm.queueSize.collectAsStateWithLifecycle()
    val playing by vm.playing.collectAsStateWithLifecycle()
    val buffering by vm.buffering.collectAsStateWithLifecycle()
    val position by vm.position.collectAsStateWithLifecycle()
    val duration by vm.duration.collectAsStateWithLifecycle()
    val saved by vm.session.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val options=saved?.options ?: settings.playback
    Panel("第 ${if(size==0) 0 else index+1}/$size 筆 · ${item?.category ?: "尚無教材"}") {
        Text("${options.native} → ${options.target} · ${directionLabel(options.direction)}")
        val current=item
        if(current!=null) {
            Text(current.text(options.native),style=MaterialTheme.typography.headlineSmall)
            Text(current.text(options.target),style=MaterialTheme.typography.headlineLarge,fontWeight=FontWeight.Bold)
            if(options.thirdLanguage) Text(PlaybackOptions.LANGUAGES.filter { it!=options.native && it!=options.target }.joinToString { current.text(it) })
            if(current.pronunciationNote.isNotBlank()) Text(current.pronunciationNote)
        } else Text("新增公開 Google Sheets 或建立示範教材場次後即可開始。")
        if(buffering) Text("正在以離線語音合成音訊，首次播放可能需要一些時間…")
        Slider(value=if(duration>0) (position.toFloat()/duration).coerceIn(0f,1f) else 0f,
            onValueChange=vm::seek,enabled=duration>0,modifier=Modifier.fillMaxWidth())
        Text("${position/1000} / ${duration/1000} 秒",style=MaterialTheme.typography.labelMedium)
        Actions {
            OutlinedButton(onClick=vm::previous,enabled=size>0) { Text("上一筆") }
            Button(onClick=vm::toggle,modifier=Modifier.heightIn(min=64.dp)) {
                Icon(if(playing) Icons.Default.Pause else Icons.Default.PlayArrow,contentDescription=null)
                Text(if(playing) "暫停" else "播放")
            }
            OutlinedButton(onClick=vm::next,enabled=size>0) { Text("下一筆") }
        }
        Actions {
            OutlinedButton(onClick=vm::next,enabled=item!=null) { Text("跳過本次") }
            OutlinedButton(onClick={ vm.mark("learned") },enabled=item!=null) { Text("已學會") }
            OutlinedButton(onClick={ vm.mark("7") },enabled=item!=null) { Text("暫停 7 天") }
            OutlinedButton(onClick={ vm.mark("30") },enabled=item!=null) { Text("暫停 30 天") }
            OutlinedButton(onClick={ vm.mark("favorite") },enabled=item!=null) { Text(if(progress?.favorite==true) "取消收藏" else "收藏") }
            OutlinedButton(onClick={ vm.mark("review") },enabled=item!=null) { Text(if((progress?.reviewPriority ?: 0)>0) "取消複習" else "加入複習") }
            OutlinedButton(onClick={ vm.mark("reset") },enabled=item!=null) { Text("恢復未學") }
        }
        Text("已播放 ${progress?.playCount ?: 0} 次 · ${if(progress?.learned==true) "已學會" else "未學會"}")
    }
    OptionsEditor(settings.playback,onSave={ vm.saveSettings(settings.copy(playback=it)) },onStart=vm::start)
}

private fun directionLabel(value: String)=when(value) { "target-native"->"目標語 → 母語"; "target"->"僅目標語"; else->"母語 → 目標語" }

@Composable
private fun Choice(label: String, value: String, choices: List<Pair<String,String>>, onChange: (String)->Unit) {
    var open by remember { mutableStateOf(false) }
    Column {
        Text(label,style=MaterialTheme.typography.labelLarge)
        Box {
            OutlinedButton(onClick={ open=true }) { Text(choices.firstOrNull { it.first==value }?.second ?: value.ifBlank { "全部" }) }
            DropdownMenu(expanded=open,onDismissRequest={ open=false }) {
                choices.forEach { (key,name) -> DropdownMenuItem(text={ Text(name) },onClick={ onChange(key); open=false }) }
            }
        }
    }
}

@Composable
private fun Toggle(label: String, checked: Boolean, onChange: (Boolean)->Unit) {
    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.SpaceBetween) {
        Text(label,Modifier.weight(1f)); Switch(checked=checked,onCheckedChange=onChange)
    }
}

@Composable
private fun OptionsEditor(saved: PlaybackOptions, onSave: (PlaybackOptions)->Unit, onStart: (PlaybackOptions)->Unit) {
    var options by remember(saved) { mutableStateOf(saved) }
    var count by remember(saved) { mutableStateOf(if(saved.count==0) "全部" else saved.count.toString()) }
    var error by remember { mutableStateOf<String?>(null) }
    fun validated(action: (PlaybackOptions)->Unit) {
        try {
            val amount=if(count=="全部") 0 else requireNotNull(count.toIntOrNull()) { "請輸入正整數或「全部」" }.also { require(it>0) { "自訂數量需為正整數" } }
            val value=options.copy(count=amount); value.validate(); error=null; action(value)
        } catch(e: Exception) { error=e.message ?: "設定不合法" }
    }
    Panel("場次與朗讀設定") {
        Text("以下變更於下次建立場次生效；目前佇列、語速與朗讀順序不會被清空或改寫。")
        val languages=listOf("zh-TW" to "繁體中文", "en-US" to "English", "id-ID" to "Bahasa Indonesia")
        Choice("母語",options.native,languages) { options=options.copy(native=it) }
        Choice("目標語",options.target,languages) { options=options.copy(target=it) }
        Choice("朗讀順序",options.direction,listOf("native-target","target-native","target").map { it to directionLabel(it) }) { options=options.copy(direction=it) }
        Toggle("顯示第三語言",options.thirdLanguage) { options=options.copy(thirdLanguage=it) }
        Choice("場次數量",count,listOf("20","50","100","全部").map { it to it }) { count=it }
        OutlinedTextField(value=count,onValueChange={ count=it },label={ Text("自訂正整數或全部") },singleLine=true,modifier=Modifier.fillMaxWidth())
        Toggle("亂數（單場次不重複）",options.random) { options=options.copy(random=it) }
        Toggle("排除已學會",options.excludeLearned) { options=options.copy(excludeLearned=it) }
        Toggle("優先複習",options.reviewFirst) { options=options.copy(reviewFirst=it) }
        Toggle("循環場次",options.loop) { options=options.copy(loop=it) }
        OutlinedTextField(options.category,{ options=options.copy(category=it) },label={ Text("分類（留空為全部）") },modifier=Modifier.fillMaxWidth())
        Choice("教材類型",options.type,listOf("" to "全部","word" to "單字","phrase" to "短語","sentence" to "句子")) { options=options.copy(type=it) }
        OutlinedTextField(options.level,{ options=options.copy(level=it) },label={ Text("難度（留空為全部）") },modifier=Modifier.fillMaxWidth())
        Choice("狀態",options.state,listOf("all" to "全部","learned" to "已學會","favorite" to "收藏","review" to "待複習")) { options=options.copy(state=it,excludeLearned=if(it=="learned") false else options.excludeLearned) }
        Choice("避開近期教材",options.avoidRecentDays.toString(),listOf("0" to "不排除","1" to "最近 1 天","7" to "最近 7 天","30" to "最近 30 天")) { options=options.copy(avoidRecentDays=it.toInt()) }
        languages.forEach { (tag,label) ->
            Text("$label 語速：${"%.2f".format(options.rate(tag))} 倍")
            Slider(value=options.rate(tag),valueRange=.25f..2f,onValueChange={ value -> options=when(tag) {
                "zh-TW"->options.copy(zhRate=value); "en-US"->options.copy(enRate=value); else->options.copy(idRate=value)
            } })
        }
        Text("每筆重複 ${options.repeats} 次")
        Slider(value=options.repeats.toFloat(),valueRange=1f..10f,steps=8,onValueChange={ options=options.copy(repeats=it.toInt()) })
        Text("每段間隔 ${options.gapSeconds} 秒")
        Slider(value=options.gapSeconds.toFloat(),valueRange=0f..30f,steps=29,onValueChange={ options=options.copy(gapSeconds=it.toInt()) })
        error?.let { Text(it,color=MaterialTheme.colorScheme.error) }
        Actions {
            OutlinedButton(onClick={ validated(onSave) }) { Text("儲存設定") }
            Button(onClick={ validated(onStart) }) { Text("建立新場次") }
        }
    }
}

@UnstableApi @Composable
private fun LibraryScreen(vm: ListenerViewModel) {
    val syncStatus by vm.syncStatus.collectAsStateWithLifecycle()
    val sources by vm.sources.collectAsStateWithLifecycle()
    val categories by vm.categories.collectAsStateWithLifecycle()
    val items by vm.library.collectAsStateWithLifecycle()
    val more by vm.hasMore.collectAsStateWithLifecycle()
    var name by rememberSaveable { mutableStateOf("") }
    var url by rememberSaveable { mutableStateOf("") }
    var query by rememberSaveable { mutableStateOf("") }
    var category by rememberSaveable { mutableStateOf("") }
    var remove by remember { mutableStateOf<Source?>(null) }
    LaunchedEffect(Unit) { vm.search(query,category) }
    Panel("Google Sheets 來源") {
        Text(when(syncStatus) {
            androidx.work.WorkInfo.State.ENQUEUED -> "同步已排程，等待符合條件的網路"
            androidx.work.WorkInfo.State.RUNNING -> "同步中，離線教材仍可使用"
            androidx.work.WorkInfo.State.SUCCEEDED -> "上次同步工作已完成"
            androidx.work.WorkInfo.State.FAILED -> "上次同步失敗，請查看來源錯誤"
            androidx.work.WorkInfo.State.CANCELLED -> "同步已取消"
            else -> "尚未排程同步"
        })
        Text("使用公開試算表網址或發布為 CSV 的網址；教材需有穩定 ID 與 zh-TW、en-US、id-ID 欄位。")
        OutlinedTextField(name,{ name=it },label={ Text("來源名稱") },modifier=Modifier.fillMaxWidth())
        OutlinedTextField(url,{ url=it },label={ Text("Google Sheets URL") },modifier=Modifier.fillMaxWidth())
        Actions {
            Button(onClick={ vm.addSource(name,url) },enabled=url.isNotBlank()) { Text("新增來源") }
            OutlinedButton(onClick=vm::sync) { Text("同步全部來源") }
        }
        sources.forEach { source ->
            HorizontalDivider()
            Text(source.name,style=MaterialTheme.typography.titleMedium)
            Text(if(source.url.isEmpty()) "離線示範教材" else source.url)
            Text("最後同步：${if(source.lastSync==0L) "尚未同步" else date(source.lastSync)}")
            source.error?.let { Text("同步失敗（保留快取）：$it",color=MaterialTheme.colorScheme.error) }
            TextButton(onClick={ remove=source }) { Text("移除來源") }
        }
    }
    Panel("教材分類") {
        if(categories.isEmpty()) Text("目前沒有教材，請新增來源並同步。")
        categories.forEach { Text("${it.category} · ${it.type} · ${it.total} 筆") }
    }
    Panel("搜尋離線教材") {
        OutlinedTextField(query,{ query=it },label={ Text("搜尋三語文字") },modifier=Modifier.fillMaxWidth())
        Choice("分類",category,listOf("" to "全部")+categories.map { it.category }.distinct().map { it to it }) { category=it }
        Button(onClick={ vm.search(query,category) }) { Text("搜尋") }
        items.forEach { item ->
            HorizontalDivider()
            Text("${item.zh}\n${item.en}\n${item.id}")
            Text("${item.category} · ${item.type} · ${listOf("zh-TW" to item.zh,"en-US" to item.en,"id-ID" to item.id).filter { it.second.isNotBlank() }.joinToString { it.first }}",style=MaterialTheme.typography.labelMedium)
            Actions {
                TextButton(onClick={ vm.markLibrary(item,true) }) { Text("已學會") }
                TextButton(onClick={ vm.markLibrary(item,false) }) { Text("恢復未學") }
            }
        }
        if(more) OutlinedButton(onClick={ vm.search(query,category,true) }) { Text("載入下 100 筆") }
    }
    Panel("AI 教材") { Text("尚未開放（Phase 3）：供應商、BYOK、產生教材、人工審核及授權寫回 Sheets。") }
    remove?.let { source -> AlertDialog(onDismissRequest={ remove=null },title={ Text("移除來源？") },
        text={ Text("${source.name} 的教材將軟刪除；保留學習紀錄及既有場次，未來以相同穩定鍵還原可繼續使用。") },
        confirmButton={ TextButton(onClick={ vm.removeSource(source.sourceId); remove=null }) { Text("移除") } },
        dismissButton={ TextButton(onClick={ remove=null }) { Text("取消") } }) }
}

@UnstableApi @Composable
private fun SettingsScreen(vm: ListenerViewModel) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val status by vm.voiceStatus.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val context=LocalContext.current
    var full by rememberSaveable { mutableStateOf(true) }
    var pendingFull by rememberSaveable { mutableStateOf(true) }
    val export=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri -> uri?.let { vm.export(it,pendingFull) } }
    val import=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(vm::import) }
    Panel("備份與換機") {
        Text("標準 ZIP 格式，不加密。請將備份保存在信任的位置；不包含 API Key、OAuth Token、憑證、合成音訊或 TTS 語音包。")
        Choice("備份內容",if(full) "full" else "light",listOf("light" to "僅學習紀錄","full" to "完整備份")) { full=it=="full" }
        Text(if(full) "包含離線教材、來源、學習進度、播放清單與設定。" else "包含來源、學習進度、播放清單與設定；不含教材，換機後需重新同步。")
        Actions {
            Button(enabled=!busy,onClick={ pendingFull=full; export.launch("LanguageListener-${System.currentTimeMillis()}.zip") }) { Text("匯出 ZIP") }
            OutlinedButton(enabled=!busy,onClick={ import.launch(arrayOf("application/zip","application/octet-stream")) }) { Text("匯入與預覽") }
        }
        Text("可透過 USB、Quick Share 或 Google Drive 手動移轉；解壓安全上限為 128 MiB。")
    }
    Panel("離線語音包") {
        Text("只有引擎回報已安裝、且不需網路的 voice 才會用於合成。各引擎與裝置支援不同，需在飛航模式實測。")
        status.forEach { (tag,available) -> Text("$tag：${if(available) "可用離線語音" else "缺少離線語音包"}") }
        Actions {
            Button(onClick=vm::checkVoices,enabled=!busy) { Text("檢查語音包") }
            OutlinedButton(onClick={
                try { context.startActivity(Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA)) }
                catch(_: android.content.ActivityNotFoundException) { vm.message.value="此裝置未提供語音包安裝畫面，請至系統的文字轉語音設定安裝" }
            }) { Text("安裝語音包") }
        }
        Text("耳機播放／暫停、上一筆、下一筆由 MediaSession 提供。特殊手勢依耳機與系統支援；來電等暫時失去音訊焦點時會暫停，焦點恢復後由系統恢復。拔除耳機會暫停。")
    }
    Panel("介面與同步") {
        Toggle("深色模式",settings.dark) { vm.saveSettings(settings.copy(dark=it)) }
        Toggle("啟動時同步",settings.syncOnLaunch) { vm.saveSettings(settings.copy(syncOnLaunch=it)) }
        Toggle("同步僅使用 Wi-Fi",settings.wifiOnly) { vm.saveSettings(settings.copy(wifiOnly=it)) }
        Text("變更同步網路政策會取消舊工作並重新排程；僅 Wi-Fi 模式也要求網路非計量。")
        Text("介面語言：臺灣繁體中文。English／Bahasa Indonesia 介面於 Phase 2 開放；三語教材與朗讀已支援。")
    }
}

private fun date(value: Long): String=DateFormat.getDateTimeInstance().format(Date(value))
