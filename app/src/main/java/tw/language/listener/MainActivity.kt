@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package tw.language.listener

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.LibraryBooks
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.util.UnstableApi
import tw.language.listener.data.*
import tw.language.listener.i18n.UiStrings

@UnstableApi
class MainActivity: androidx.appcompat.app.AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState);setContent { ListenerScreen() } }
}

@UnstableApi @Composable
fun ListenerScreen(vm: ListenerViewModel=viewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val language by vm.language.collectAsStateWithLifecycle()
    val savedTab by vm.tab.collectAsStateWithLifecycle()
    val tab=savedTab ?: 0
    val message by vm.message.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val restored by vm.restored.collectAsStateWithLifecycle()
    val sources by vm.sources.collectAsStateWithLifecycle()
    val ui=UiStrings(LocalContext.current)
    val snackbar=remember { SnackbarHostState() }
    var stack by rememberSaveable { mutableStateOf(listOf(AppRoutes.entry("root"))) }
    val entry=stack.last();val page=AppRoutes.page(entry);val sourceId=AppRoutes.source(entry)
    val navigator=AppNavigator(open={ next,id -> stack=stack+AppRoutes.entry(next,id) },back={ if(stack.size>1) stack=stack.dropLast(1) })
    val savedStates=rememberSaveableStateHolder()
    var lastTab by rememberSaveable { mutableIntStateOf(-1) }
    LaunchedEffect(savedTab) { if(savedTab!=null) { if(lastTab!=-1 && lastTab!=tab) stack=listOf(AppRoutes.entry("root"));lastTab=tab } }
    LaunchedEffect(restored) { if(restored) { navigator.go("restored");vm.restored.value=false } }
    LaunchedEffect(language) { language?.let {
        val locales=androidx.core.os.LocaleListCompat.forLanguageTags(it)
        if(androidx.appcompat.app.AppCompatDelegate.getApplicationLocales()!=locales) androidx.appcompat.app.AppCompatDelegate.setApplicationLocales(locales)
    } }
    val notification=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) { if(Build.VERSION.SDK_INT>=33) notification.launch(Manifest.permission.POST_NOTIFICATIONS) }
    if(language==null || savedTab==null) { Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center) { CircularProgressIndicator() };return }
    val colors=if(settings.dark) darkColorScheme(primary=Color(0xFF8BC1FA),onPrimary=Color(0xFF10243D),secondary=Color(0xFF8BC1FA),onSecondary=Color(0xFF10243D),secondaryContainer=Color(0xFF233E60),onSecondaryContainer=Color(0xFFD7E9FF),background=Color(0xFF101B29),surface=Color(0xFF192A3F),onBackground=Color(0xFFE8F0FB),onSurface=Color(0xFFE8F0FB),primaryContainer=Color(0xFF233E60),onPrimaryContainer=Color(0xFFD7E9FF),surfaceVariant=Color(0xFF23364D),onSurfaceVariant=Color(0xFFAFBED2))
    else lightColorScheme(primary=Color(0xFF2867A7),onPrimary=Color.White,secondary=Color(0xFF2867A7),onSecondary=Color.White,secondaryContainer=Color(0xFFE8F3FF),onSecondaryContainer=Color(0xFF2867A7),background=Color(0xFFF6F8FC),surface=Color.White,onSurface=Color(0xFF172A43),onBackground=Color(0xFF172A43),primaryContainer=Color(0xFFE8F3FF),onPrimaryContainer=Color(0xFF2867A7),surfaceVariant=Color(0xFFEBF0F7),onSurfaceVariant=Color(0xFF5C6E84))
    MaterialTheme(colorScheme=colors) { CompositionLocalProvider(LocalAppNavigator provides navigator) {
        ServiceEffects(report={ vm.message.value=it })
        LaunchedEffect(message) { message?.let { snackbar.showSnackbar(ui.message(it));vm.message.value=null } }
        BackHandler(stack.size>1 || tab!=0) { if(stack.size>1) navigator.back() else vm.chooseTab(0) }
        val labels=listOf(ui(R.string.home_fdf1f0),ui(R.string.player_aa109d),ui(R.string.library_9de5bd),ui(R.string.settings_6329f2))
        val icons=listOf(Icons.Default.Home,Icons.Default.Headphones,Icons.AutoMirrored.Filled.LibraryBooks,Icons.Default.Settings)
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val wide=maxWidth>=840.dp
            Row(Modifier.fillMaxSize()) {
                if(wide) NavigationRail(containerColor=colors.surface,header={ Text("LL",style=MaterialTheme.typography.headlineSmall,modifier=Modifier.padding(16.dp),color=colors.primary) }) {
                    labels.forEachIndexed { i,label -> NavigationRailItem(selected=tab==i,onClick={ stack=listOf(AppRoutes.entry("root"));vm.chooseTab(i) },label={ Text(label) },icon={ Icon(icons[i],null) }) }
                }
                Scaffold(modifier=Modifier.weight(1f),containerColor=colors.background,topBar={
                    TopAppBar(colors=TopAppBarDefaults.topAppBarColors(containerColor=colors.background),title={ Text(if(page=="root") if(tab==0) "Language Listener" else labels[tab.coerceIn(0,3)] else if(page=="source") sources.find { it.sourceId==sourceId }?.name ?: ui.message("教材來源") else ui.message(AppRoutes.title(page)),maxLines=1,overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis) },navigationIcon={
                        if(page!="root") IconButton(onClick=navigator.back) { Icon(Icons.AutoMirrored.Filled.ArrowBack,tr("返回")) }
                    },actions={ Text("v${BuildConfig.VERSION_NAME}",style=MaterialTheme.typography.labelSmall,color=colors.onSurfaceVariant,modifier=Modifier.padding(end=16.dp)) })
                },snackbarHost={ SnackbarHost(snackbar) },bottomBar={ if(!wide) NavigationBar(containerColor=colors.surface) {
                    labels.forEachIndexed { i,label -> NavigationBarItem(selected=tab==i,onClick={ stack=listOf(AppRoutes.entry("root"));vm.chooseTab(i) },label={ Text(label) },icon={ Icon(icons[i],null) }) }
                } }) { padding ->
                    Column(Modifier.padding(padding).fillMaxSize().imePadding()) {
                        if(busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                        savedStates.SaveableStateProvider("$tab:$entry") {
                            val scroll=rememberScrollState()
                            Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(horizontal=24.dp,vertical=12.dp),verticalArrangement=Arrangement.spacedBy(18.dp),horizontalAlignment=Alignment.CenterHorizontally) {
                                Column(Modifier.widthIn(max=if(wide && page=="root" && tab==2) 1200.dp else 760.dp).fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(18.dp)) {
                                    when(page) {
                                        "root" -> when(tab) { 0->HomeScreen(vm);1->PlayerScreen(vm);2->SourceLibrary(vm,wide);else->SettingsHome(vm) }
                                        "new" -> NewSourceScreen(vm)
                                        "files" -> DocumentPanel(vm,{ vm.message.value=it },sourceFilter=sourceId.ifBlank { null })
                                        "google" -> { GoogleAuthoringPanel({ vm.message.value=it });PublicSourcePanel(vm) }
                                        "ai" -> AiAuthoringPanel(sources,vm::studyDraft,{ vm.checkVoices();navigator.go("voices") },mode="generate",onSettings={ navigator.go("ai-settings") },onReview={ navigator.go("review") })
                                        "ai-settings" -> AiAuthoringPanel(sources,vm::studyDraft,{ navigator.go("voices") },mode="settings",onSettings={},onReview={ navigator.go("review") })
                                        "review" -> AiAuthoringPanel(sources,vm::studyDraft,{ vm.checkVoices();navigator.go("voices") },mode="review",onSettings={ navigator.go("ai-settings") },onReview={})
                                        "source" -> SourceDetail(sourceId,vm)
                                        "browse" -> LessonBrowser(sourceId,vm)
                                        "dictionary" -> DictionaryActions(sourceId)
                                        "session","filters","speech" -> { OptionsEditor(settings.playback,{ vm.saveSettings(settings.copy(playback=it)) },{ value -> stack=listOf(AppRoutes.entry("root"));vm.start(value) },sources,page);if(page=="session") { MenuLink("進階篩選","分類、難度、狀態與近期排除",Icons.Default.FilterList) { navigator.go("filters") };MenuLink("朗讀設定","順序、各語速、重複與間隔",Icons.AutoMirrored.Filled.VolumeUp) { navigator.go("speech") } } }
                                        "review-stats" -> ReviewDashboard(vm)
                                        "queue" -> QueueScreen(vm)
                                        "voices","backup","appearance","preferences" -> SettingsDetail(vm,page)
                                        "restored" -> { EmptyCard("還原完成","教材與學習紀錄已還原；語音包與檔案權限需另行準備。",Icons.Default.CheckCircle);MenuLink("離線語音包","檢查這台裝置的語音",Icons.AutoMirrored.Filled.VolumeUp) { navigator.go("voices") };MenuLink("本機 Excel／CSV","接回既有來源與進度",Icons.Default.Folder) { navigator.go("files") } }
                                    }
                                    Spacer(Modifier.height(8.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
        RestorePreview(vm)
    } }
}

internal fun directionLabel(value: String,ui: UiStrings)=when(value) { "target-native"->ui(R.string.target_native_9fb69a);"target"->ui(R.string.target_language_only_f1e468);else->ui(R.string.native_target_632dc2) }
