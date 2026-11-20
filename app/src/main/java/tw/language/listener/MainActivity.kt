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
class MainActivity : androidx.appcompat.app.AppCompatActivity() {
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
    val ui=tw.language.listener.i18n.UiStrings(context)
    val language by vm.language.collectAsStateWithLifecycle()
    LaunchedEffect(language) {
        language?.let {
            val locales=androidx.core.os.LocaleListCompat.forLanguageTags(it)
            if(androidx.appcompat.app.AppCompatDelegate.getApplicationLocales()!=locales)
                androidx.appcompat.app.AppCompatDelegate.setApplicationLocales(locales)
        }
    }
    if(language==null) { CircularProgressIndicator(); return }
    val notification=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) { if(Build.VERSION.SDK_INT>=33) notification.launch(Manifest.permission.POST_NOTIFICATIONS) }
    LaunchedEffect(message) { message?.let { snack.showSnackbar(ui.message(it)); vm.message.value=null } }
    val colors=if(settings.dark) darkColorScheme(primary=Color(0xFF8BC1FF)) else lightColorScheme(
        primary=Color(0xFF397FC9),onBackground=Color(0xFF172A43),background=Color.White,primaryContainer=Color(0xFFE8F3FF))
    MaterialTheme(colorScheme=colors) {
        BackHandler(tab!=0) { vm.chooseTab(0) }
        Scaffold(topBar={ TopAppBar(title={ Text("Language Listener") },actions={ Text("v${BuildConfig.VERSION_NAME}",modifier=Modifier.padding(horizontal=12.dp)) }) },
            snackbarHost={ SnackbarHost(snack) },
            bottomBar={ NavigationBar {
                listOf(ui(R.string.home_fdf1f0),ui(R.string.player_aa109d),ui(R.string.library_9de5bd),ui(R.string.settings_6329f2)).forEachIndexed { index,label ->
                    NavigationBarItem(selected=tab==index,onClick={ vm.chooseTab(index) },label={ Text(label) },icon={
                        Icon(when(index) { 0->Icons.Default.Home; 1->Icons.Default.Headphones; 2->Icons.Default.LibraryBooks; else->Icons.Default.Settings },contentDescription=null)
                    })
                }
            } }) { padding ->
            Column(Modifier.padding(padding).fillMaxSize().imePadding()) {
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
            AlertDialog(onDismissRequest={ if(!busy) vm.dismissPreview() },title={ Text(ui(R.string.import_preview_460dc3)) },text={
                Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    Text(ui(R.string.date_version_schema_lessons_learning_records_sou_f0b022, (ui.date(pending.manifest.createdAt)), (pending.manifest.appVersion), (pending.manifest.schemaVersion), (pending.manifest.itemCount), (pending.manifest.progressCount), (pending.manifest.sourceCount)))
                    pending.sources.forEach { Text(it.name) }
                    Text(ui(R.string.backup_replace_deletes_current_data_smart_merge_445fe0, (if(pending.manifest.full) ui(R.string.full_6e6e58) else ui(R.string.learning_records_only_d16fb6))))
                    if(!pending.manifest.full) Text(ui(R.string.lightweight_backups_exclude_lesson_text_after_re_c3f7cd))
                }
            },confirmButton={ TextButton(enabled=!busy,onClick={ vm.restore(false) }) { Text(ui(R.string.smart_merge_0494db)) } },dismissButton={
                Column {
                    TextButton(enabled=!busy,onClick={ vm.restore(true) }) { Text(ui(R.string.replace_all_720837)) }
                    TextButton(enabled=!busy,onClick=vm::dismissPreview) { Text(ui(R.string.cancel_4d0b46)) }
                }
            })
        }
    }
}

@Composable
private fun NetworkStatus() {
    val ui=tw.language.listener.i18n.UiStrings(androidx.compose.ui.platform.LocalContext.current)
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
    Text(if(online) ui(R.string.online_lessons_use_offline_voices_on_this_device_395220) else ui(R.string.offline_downloaded_lessons_are_available_a21fca),style=MaterialTheme.typography.labelLarge)
}

@Composable
internal fun Panel(title: String, content: @Composable ColumnScope.()->Unit) {
    Card(Modifier.fillMaxWidth(),colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.primaryContainer)) {
        Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Text(title,style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold)
            content()
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun Actions(content: @Composable FlowRowScope.()->Unit) {
    FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp),verticalArrangement=Arrangement.spacedBy(4.dp),content=content)
}

@UnstableApi @Composable
private fun Home(vm: ListenerViewModel) {
    val ui=tw.language.listener.i18n.UiStrings(androidx.compose.ui.platform.LocalContext.current)
    val stats by vm.stats.collectAsStateWithLifecycle()
    val streak by vm.streak.collectAsStateWithLifecycle()
    val saved by vm.session.collectAsStateWithLifecycle()
    val total by vm.queueSize.collectAsStateWithLifecycle()
    val current by vm.item.collectAsStateWithLifecycle()
    Panel(ui(R.string.listen_a_little_every_day_let_languages_feel_nat_79d6bf)) {
        Text(ui(R.string.lessons_learned_favorites_learning_streak_days_20e9df, (stats.total), (stats.learned), (stats.favorite), (streak)))
    }
    ReviewDashboard(vm)
    Panel(ui(R.string.continue_learning_66b50b)) {
        Text(ui(R.string.entries_1b770f, (current?.category ?: ui(R.string.no_session_yet_ab52a9)), (if(total>0) (saved?.position ?: 0)+1 else 0), (total)))
        LinearProgressIndicator(progress={ if(total==0) 0f else ((saved?.position ?: 0)+1f)/total },modifier=Modifier.fillMaxWidth())
        Button(onClick={ vm.chooseTab(1) }) { Text(ui(R.string.open_player_74ee10)) }
    }
    Panel(ui(R.string.quick_start_72add7)) {
        Actions {
            Button(onClick={ vm.start(vm.settings.value.playback.copy(type="word")) }) { Text(ui(R.string.word_practice_1f71d6)) }
            Button(onClick={ vm.start(vm.settings.value.playback.copy(type="sentence")) }) { Text(ui(R.string.sentence_listening_de9200)) }
            Button(onClick={ vm.start(vm.settings.value.playback.copy(random=true)) }) { Text(ui(R.string.random_learning_f41522)) }
            Button(onClick={ vm.start(vm.settings.value.playback.copy(state="learned",excludeLearned=false)) }) { Text(ui(R.string.review_learned_entries_96a409)) }
        }
    }
}

@UnstableApi @Composable
private fun PlayerScreen(vm: ListenerViewModel) {
    val ui=tw.language.listener.i18n.UiStrings(androidx.compose.ui.platform.LocalContext.current)
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
    Panel(ui(R.string.entry_71375b, (if(size==0) 0 else index+1), (size), (item?.category ?: ui(R.string.no_lessons_yet_1ad296)))) {
        Text("${options.native} → ${options.target} · ${directionLabel(options.direction,ui)}")
        val current=item
        if(current!=null) {
            Text(current.text(options.native),style=MaterialTheme.typography.headlineSmall)
            Text(current.text(options.target),style=MaterialTheme.typography.headlineLarge,fontWeight=FontWeight.Bold)
            if(options.thirdLanguage) Text(PlaybackOptions.LANGUAGES.filter { it!=options.native && it!=options.target }.joinToString { current.text(it) })
            if(current.pronunciationNote.isNotBlank()) Text(current.pronunciationNote)
        } else Text(ui(R.string.add_public_google_sheets_or_create_a_session_wit_dcfde8))
        if(buffering) Text(ui(R.string.synthesizing_offline_audio_the_first_playback_ma_4a780a))
        Slider(value=if(duration>0) (position.toFloat()/duration).coerceIn(0f,1f) else 0f,
            onValueChange=vm::seek,enabled=duration>0,modifier=Modifier.fillMaxWidth())
        Text(ui(R.string.seconds_b14097, (position/1000), (duration/1000)),style=MaterialTheme.typography.labelMedium)
        Actions {
            OutlinedButton(onClick=vm::previous,enabled=size>0) { Text(ui(R.string.previous_entry_7f3fd8)) }
            Button(onClick=vm::toggle,modifier=Modifier.heightIn(min=64.dp)) {
                Icon(if(playing) Icons.Default.Pause else Icons.Default.PlayArrow,contentDescription=null)
                Text(if(playing) ui(R.string.pause_37676b) else ui(R.string.play_219253))
            }
            OutlinedButton(onClick=vm::next,enabled=size>0) { Text(ui(R.string.next_entry_58bf90)) }
        }
        Actions {
            OutlinedButton(onClick=vm::next,enabled=item!=null) { Text(ui(R.string.skip_this_time_9b9b43)) }
            OutlinedButton(onClick={ vm.mark("learned") },enabled=item!=null) { Text(ui(R.string.learned_0f130c)) }
            OutlinedButton(onClick={ vm.mark("7") },enabled=item!=null) { Text(ui(R.string.suspend_for_7_days_c6ba96)) }
            OutlinedButton(onClick={ vm.mark("30") },enabled=item!=null) { Text(ui(R.string.suspend_for_30_days_31f064)) }
            OutlinedButton(onClick={ vm.mark("favorite") },enabled=item!=null) { Text(if(progress?.favorite==true) ui(R.string.remove_favorite_26acae) else ui(R.string.favorite_d07cee)) }
            OutlinedButton(onClick={ vm.mark("review") },enabled=item!=null) { Text(if((progress?.reviewPriority ?: 0)>0) ui(R.string.remove_from_review_35169d) else ui(R.string.add_to_review_aec981)) }
            OutlinedButton(onClick={ vm.mark("reset") },enabled=item!=null) { Text(ui(R.string.reset_to_unlearned_6ddb59)) }
        }
        Text(ui(R.string.played_times_79b9e3, (progress?.playCount ?: 0), (if(progress?.learned==true) ui(R.string.learned_0f130c) else ui(R.string.not_learned_2a214f))))
    }
    ReviewControls(vm)
    OptionsEditor(settings.playback,onSave={ vm.saveSettings(settings.copy(playback=it)) },onStart=vm::start)
}

private fun directionLabel(value: String, ui: tw.language.listener.i18n.UiStrings)=when(value) { "target-native"->ui(R.string.target_native_9fb69a); "target"->ui(R.string.target_language_only_f1e468); else->ui(R.string.native_target_632dc2) }

@Composable
internal fun Choice(label: String, value: String, choices: List<Pair<String,String>>, onChange: (String)->Unit) {
    val ui=tw.language.listener.i18n.UiStrings(androidx.compose.ui.platform.LocalContext.current)
    var open by remember { mutableStateOf(false) }
    Column {
        Text(label,style=MaterialTheme.typography.labelLarge)
        Box {
            OutlinedButton(onClick={ open=true }) { Text(choices.firstOrNull { it.first==value }?.second ?: value.ifBlank { ui(R.string.all_778fc8) }) }
            DropdownMenu(expanded=open,onDismissRequest={ open=false }) {
                choices.forEach { (key,name) -> DropdownMenuItem(text={ Text(name) },onClick={ onChange(key); open=false }) }
            }
        }
    }
}

@Composable
private fun Toggle(label: String, checked: Boolean, onChange: (Boolean)->Unit) {
    val ui=tw.language.listener.i18n.UiStrings(androidx.compose.ui.platform.LocalContext.current)
    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.SpaceBetween) {
        Text(label,Modifier.weight(1f)); Switch(checked=checked,onCheckedChange=onChange)
    }
}

@Composable
private fun OptionsEditor(saved: PlaybackOptions, onSave: (PlaybackOptions)->Unit, onStart: (PlaybackOptions)->Unit) {
    val ui=tw.language.listener.i18n.UiStrings(androidx.compose.ui.platform.LocalContext.current)
    var options by remember(saved) { mutableStateOf(saved) }
    var count by remember(saved) { mutableStateOf(if(saved.count==0) "全部" else saved.count.toString()) }
    var error by remember { mutableStateOf<String?>(null) }
    fun validated(action: (PlaybackOptions)->Unit) {
        try {
            val amount=if(count=="全部") 0 else requireNotNull(count.toIntOrNull()) { "請輸入正整數或「全部」" }.also { require(it>0) { "自訂數量需為正整數" } }
            val value=options.copy(count=amount); value.validate(); error=null; action(value)
        } catch(e: Exception) { error=e.message ?: "設定不合法" }
    }
    Panel(ui(R.string.session_and_speech_settings_8673cd)) {
        Text(ui(R.string.changes_below_apply_when_creating_the_next_sessi_20ffb6))
        val languages=listOf("zh-TW" to "繁體中文", "en-US" to "English", "id-ID" to "Bahasa Indonesia")
        Choice(ui(R.string.native_language_9bbc85),options.native,languages) { options=options.copy(native=it) }
        Choice(ui(R.string.target_language_651506),options.target,languages) { options=options.copy(target=it) }
        Choice(ui(R.string.speech_order_cfb84b),options.direction,listOf("native-target","target-native","target").map { it to directionLabel(it,ui) }) { options=options.copy(direction=it) }
        Toggle(ui(R.string.show_the_third_language_3450d3),options.thirdLanguage) { options=options.copy(thirdLanguage=it) }
        Choice(ui(R.string.session_size_666f5b),count,listOf("20","50","100","全部").map { it to if(it=="全部") ui(R.string.all_778fc8) else it }) { count=it }
        OutlinedTextField(value=if(count=="全部") ui(R.string.all_778fc8) else count,onValueChange={ count=if(it==ui(R.string.all_778fc8)) "全部" else it },label={ Text(ui(R.string.custom_positive_integer_or_all_7a1c42)) },singleLine=true,modifier=Modifier.fillMaxWidth())
        Toggle(ui(R.string.shuffle_no_repeats_within_a_session_61e644),options.random) { options=options.copy(random=it) }
        Toggle(ui(R.string.exclude_learned_entries_61e4c7),options.excludeLearned) { options=options.copy(excludeLearned=it) }
        Toggle(ui(R.string.prioritize_review_363ac5),options.reviewFirst) { options=options.copy(reviewFirst=it) }
        Toggle(ui(R.string.loop_session_366b56),options.loop) { options=options.copy(loop=it) }
        OutlinedTextField(options.category,{ options=options.copy(category=it) },label={ Text(ui(R.string.category_blank_for_all_33af15)) },modifier=Modifier.fillMaxWidth())
        Choice(ui(R.string.lesson_type_f0ab97),options.type,listOf("" to ui(R.string.all_778fc8),"word" to ui(R.string.words_d34b56),"phrase" to ui(R.string.phrases_155650),"sentence" to ui(R.string.sentences_8cfcfc))) { options=options.copy(type=it) }
        OutlinedTextField(options.level,{ options=options.copy(level=it) },label={ Text(ui(R.string.level_blank_for_all_f722d1)) },modifier=Modifier.fillMaxWidth())
        Choice(ui(R.string.status_452935),options.state,listOf("all" to ui(R.string.all_778fc8),"learned" to ui(R.string.learned_0f130c),"favorite" to ui(R.string.favorite_d07cee),"review" to ui(R.string.needs_review_d00608),"due" to ui.message("到期複習"))) { options=options.copy(state=it,excludeLearned=if(it in listOf("learned","due")) false else options.excludeLearned) }
        Choice(ui(R.string.avoid_recently_played_entries_b9e9d4),options.avoidRecentDays.toString(),listOf("0" to ui(R.string.do_not_exclude_e78264),"1" to ui(R.string.last_day_1442e5),"7" to ui(R.string.last_7_days_2a0a05),"30" to ui(R.string.last_30_days_279e4c))) { options=options.copy(avoidRecentDays=it.toInt()) }
        languages.forEach { (tag,label) ->
            Text(ui(R.string.speech_rate_0959eb, (label), (ui.decimal(options.rate(tag)))))
            Slider(value=options.rate(tag),valueRange=.25f..2f,onValueChange={ value -> options=when(tag) {
                "zh-TW"->options.copy(zhRate=value); "en-US"->options.copy(enRate=value); else->options.copy(idRate=value)
            } })
        }
        Text(ui(R.string.repeat_each_entry_times_4a864c, (options.repeats)))
        Slider(value=options.repeats.toFloat(),valueRange=1f..10f,steps=8,onValueChange={ options=options.copy(repeats=it.toInt()) })
        Text(ui(R.string.gap_between_segments_seconds_6afbef, (options.gapSeconds)))
        Slider(value=options.gapSeconds.toFloat(),valueRange=0f..30f,steps=29,onValueChange={ options=options.copy(gapSeconds=it.toInt()) })
        error?.let { Text(ui.message(it),color=MaterialTheme.colorScheme.error) }
        Actions {
            OutlinedButton(onClick={ validated(onSave) }) { Text(ui(R.string.save_settings_bcc070)) }
            Button(onClick={ validated(onStart) }) { Text(ui(R.string.create_new_session_b359ba)) }
        }
    }
}

@UnstableApi @Composable
private fun LibraryScreen(vm: ListenerViewModel) {
    val ui=tw.language.listener.i18n.UiStrings(androidx.compose.ui.platform.LocalContext.current)
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
    var editingLocal by remember { mutableStateOf<Item?>(null) }
    LaunchedEffect(Unit) { vm.search(query,category) }
    DocumentPanel(vm,report={ vm.message.value=it })
    LocalLessonsPanel(vm)
    GoogleAuthoringPanel(report={ vm.message.value=it })
    Panel(ui(R.string.sources_and_sync_public_sources_can_be_added_rea_c90c00)) {
        Text(when(syncStatus) {
            androidx.work.WorkInfo.State.ENQUEUED -> ui(R.string.sync_queued_waiting_for_an_eligible_network_d8981d)
            androidx.work.WorkInfo.State.RUNNING -> ui(R.string.syncing_offline_lessons_remain_available_d7fcfa)
            androidx.work.WorkInfo.State.SUCCEEDED -> ui(R.string.last_sync_completed_94fada)
            androidx.work.WorkInfo.State.FAILED -> ui(R.string.last_sync_failed_check_source_errors_5d23ff)
            androidx.work.WorkInfo.State.CANCELLED -> ui(R.string.sync_cancelled_dd26f7)
            else -> ui(R.string.no_sync_scheduled_fb8d3d)
        })
        Text(ui(R.string.use_a_public_spreadsheet_url_or_a_published_csv_dcabb5))
        OutlinedTextField(name,{ name=it },label={ Text(ui(R.string.source_name_0ccc8a)) },modifier=Modifier.fillMaxWidth())
        OutlinedTextField(url,{ url=it },label={ Text("Google Sheets URL") },modifier=Modifier.fillMaxWidth())
        Actions {
            Button(onClick={ vm.addSource(name,url) },enabled=url.isNotBlank()) { Text(ui(R.string.add_public_read_only_source_d4f075)) }
            OutlinedButton(onClick=vm::sync) { Text(ui(R.string.sync_all_sources_84ce63)) }
        }
        sources.forEach { source ->
            HorizontalDivider()
            Text(if(source.sourceId==Repository.LOCAL_SOURCE_ID) ui.message("我的本機教材") else source.name,style=MaterialTheme.typography.titleMedium)
            Text(if(source.access=="file") ui.message("本機 Excel／CSV 來源；請使用上方檔案同步功能") else if(source.url.isEmpty()) ui.message("本機教材，不會從 Google Sheets 同步") else if(source.access=="google") ui(R.string.google_source_writing_requires_valid_authorizati_949627) else ui(R.string.public_read_only_source_664e30))
            Text(if(source.access=="file") ui.message("本機 Excel／CSV 來源；請使用上方檔案同步功能") else if(source.url.isEmpty()) ui.message("儲存在這台裝置；請使用完整 ZIP 備份保存教材文字") else source.url)
            Text(ui(R.string.last_sync_0620ac, (if(source.lastSync==0L) ui(R.string.not_synced_yet_eeee89) else ui.date(source.lastSync))))
            source.error?.let { Text(ui(R.string.sync_failed_cache_retained_2f0d91, (ui.message(it))),color=MaterialTheme.colorScheme.error) }
            if(source.url.isNotEmpty() && source.access=="public") {
                val authoring: tw.language.listener.authoring.AuthoringViewModel=viewModel()
                TextButton(onClick={ authoring.inspect(source.name,source.url) }) { Text(ui.message("初始化這個工作表／授權新增教材")) }
            }
            TextButton(onClick={ remove=source }) { Text(ui(R.string.remove_source_0e4e14)) }
        }
    }
    Panel(ui(R.string.lesson_categories_1dff78)) {
        if(categories.isEmpty()) Text(ui(R.string.no_lessons_yet_add_a_source_and_sync_7a204c))
        categories.forEach { Text(ui(R.string.entries_09c5b2, (it.category), (it.type), (it.total))) }
    }
    Panel(ui(R.string.search_offline_lessons_b65cdf)) {
        OutlinedTextField(query,{ query=it },label={ Text(ui(R.string.search_text_in_all_three_languages_42f81d)) },modifier=Modifier.fillMaxWidth())
        Choice(ui(R.string.category_7c6fd7),category,listOf("" to ui(R.string.all_778fc8))+categories.map { it.category }.distinct().map { it to it }) { category=it }
        Button(onClick={ vm.search(query,category) }) { Text(ui(R.string.search_0a4379)) }
        items.forEach { item ->
            HorizontalDivider()
            Text("${item.zh}\n${item.en}\n${item.id}")
            Text("${item.category} · ${item.type} · ${listOf("zh-TW" to item.zh,"en-US" to item.en,"id-ID" to item.id).filter { it.second.isNotBlank() }.joinToString { it.first }}",style=MaterialTheme.typography.labelMedium)
            Actions {
                if(item.sourceId==Repository.LOCAL_SOURCE_ID || sources.any { it.sourceId==item.sourceId && it.access=="file" }) {
                    TextButton(onClick={ editingLocal=item }) { Text(ui.message("編輯本機教材")) }
                }
                TextButton(onClick={ vm.markLibrary(item,true) }) { Text(ui(R.string.learned_0f130c)) }
                TextButton(onClick={ vm.markLibrary(item,false) }) { Text(ui(R.string.reset_to_unlearned_6ddb59)) }
            }
        }
        if(more) OutlinedButton(onClick={ vm.search(query,category,true) }) { Text(ui(R.string.load_next_100_entries_a1e2e1)) }
    }
    editingLocal?.let { LocalLessonDialog(it,vm) { editingLocal=null } }
    AiAuthoringPanel(sources)
    remove?.let { source -> AlertDialog(onDismissRequest={ remove=null },title={ Text(ui(R.string.remove_source_ffd5c8)) },
        text={ Text(ui(R.string.lessons_from_will_be_soft_deleted_learning_recor_96f485, (source.name))) },
        confirmButton={ TextButton(onClick={ vm.removeSource(source.sourceId); remove=null }) { Text(ui(R.string.remove_2f752c)) } },
        dismissButton={ TextButton(onClick={ remove=null }) { Text(ui(R.string.cancel_4d0b46)) } }) }
}

@UnstableApi @Composable
private fun SettingsScreen(vm: ListenerViewModel) {
    val ui=tw.language.listener.i18n.UiStrings(androidx.compose.ui.platform.LocalContext.current)
    val settings by vm.settings.collectAsStateWithLifecycle()
    val status by vm.voiceStatus.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val context=LocalContext.current
    var full by rememberSaveable { mutableStateOf(true) }
    var pendingFull by rememberSaveable { mutableStateOf(true) }
    val export=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri -> uri?.let { vm.export(it,pendingFull) } }
    val import=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(vm::import) }
    Panel(ui(R.string.backup_and_device_transfer_f1a679)) {
        Text(ui(R.string.standard_zip_unencrypted_save_backups_only_in_tr_9df06b))
        Choice(ui(R.string.backup_contents_94ace2),if(full) "full" else "light",listOf("light" to ui(R.string.learning_records_only_d16fb6),"full" to ui(R.string.full_backup_d3d579))) { full=it=="full" }
        Text(if(full) ui(R.string.includes_offline_lessons_sources_learning_progre_2f38a3) else ui(R.string.includes_sources_learning_progress_queues_and_se_743f52))
        Actions {
            Button(enabled=!busy,onClick={ pendingFull=full; export.launch("LanguageListener-${System.currentTimeMillis()}.zip") }) { Text(ui(R.string.export_zip_89f513)) }
            OutlinedButton(enabled=!busy,onClick={ import.launch(arrayOf("application/zip","application/octet-stream")) }) { Text(ui(R.string.import_and_preview_a8bb8f)) }
        }
        Text(ui(R.string.local_light_backup_warning))
        Text(ui(R.string.transfer_manually_using_usb_quick_share_or_googl_798d78))
    }
    Panel(ui(R.string.offline_voice_packages_8ee771)) {
        Text(ui(R.string.synthesis_uses_only_voices_the_engine_reports_as_d8dada))
        status.forEach { (tag,available) -> Text("$tag：${if(available) ui(R.string.offline_voice_available_9b3a3a) else ui(R.string.offline_voice_package_missing_6689f4)}") }
        Actions {
            Button(onClick=vm::checkVoices,enabled=!busy) { Text(ui(R.string.check_voice_packages_583911)) }
            OutlinedButton(onClick={
                try { context.startActivity(Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA)) }
                catch(_: android.content.ActivityNotFoundException) { vm.message.value="此裝置未提供語音包安裝畫面，請至系統的文字轉語音設定安裝" }
            }) { Text(ui(R.string.install_voice_packages_4c0ceb)) }
        }
        Text(ui(R.string.mediasession_provides_headset_play_pause_and_pre_4b43a0))
    }
    Panel(ui(R.string.interface_and_sync_d92df4)) {
        Toggle(ui(R.string.dark_mode_750642),settings.dark) { vm.saveSettings(settings.copy(dark=it)) }
        Toggle(ui(R.string.sync_on_launch_b1fb8c),settings.syncOnLaunch) { vm.saveSettings(settings.copy(syncOnLaunch=it)) }
        Toggle(ui(R.string.sync_only_on_wi_fi_bdf678),settings.wifiOnly) { vm.saveSettings(settings.copy(wifiOnly=it)) }
        Text(ui(R.string.changing_the_network_policy_cancels_old_work_and_1b3e74))
        Choice(ui(R.string.interface_language_c5354a),settings.uiLanguage,listOf("zh-TW" to "繁體中文","en" to "English","id" to "Bahasa Indonesia")) { vm.saveSettings(settings.copy(uiLanguage=it)) }
        Text(ui(R.string.changing_the_interface_language_does_not_change_06a194))
    }
}
