@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package tw.language.listener

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import tw.language.listener.data.*
import tw.language.listener.i18n.UiStrings

@Composable internal fun NetworkStatus() {
    val context=LocalContext.current;val cm=remember { context.getSystemService(ConnectivityManager::class.java) }
    fun connected()=cm.getNetworkCapabilities(cm.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)==true
    var online by remember { mutableStateOf(connected()) }
    DisposableEffect(cm) {
        val cb=object: ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { online=connected() }
            override fun onLost(network: Network) { online=connected() }
            override fun onCapabilitiesChanged(network: Network,caps: NetworkCapabilities) { online=connected() }
        }
        cm.registerDefaultNetworkCallback(cb);onDispose { cm.unregisterNetworkCallback(cb) }
    }
    StatusTag(if(online) "教材儲存在這台裝置" else "離線模式・本機教材仍可學習")
}

@UnstableApi @Composable internal fun HomeScreen(vm: ListenerViewModel) {
    val nav=LocalAppNavigator.current;val ui=UiStrings(LocalContext.current)
    val stats by vm.stats.collectAsStateWithLifecycle();val streak by vm.streak.collectAsStateWithLifecycle()
    val saved by vm.session.collectAsStateWithLifecycle();val total by vm.queueSize.collectAsStateWithLifecycle()
    val item by vm.item.collectAsStateWithLifecycle();val due by vm.due.collectAsStateWithLifecycle()
    NetworkStatus()
    Text(ui(R.string.listen_a_little_every_day_let_languages_feel_nat_79d6bf),style=MaterialTheme.typography.headlineMedium,fontWeight=FontWeight.Bold)
    Panel("") { Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
        listOf(stats.total.toString() to tr("教材"),stats.learned.toString() to ui(R.string.learned_0f130c),streak.toString() to tr("連續學習天數")).forEach { (v,label) -> Column(Modifier.weight(1f)) { Text(v,style=MaterialTheme.typography.headlineMedium,fontWeight=FontWeight.Bold);Text(label,style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant) } }
    } }
    if(total>0) {
        Panel(ui(R.string.continue_learning_66b50b)) {
            Text(item?.category ?: ui(R.string.player_aa109d),style=MaterialTheme.typography.titleMedium)
            Text(ui(R.string.entries_1b770f,item?.category ?: "",(saved?.position ?: 0)+1,total),style=MaterialTheme.typography.bodySmall)
            LinearProgressIndicator(progress={ (((saved?.position ?: 0)+1f)/total).coerceIn(0f,1f) },modifier=Modifier.fillMaxWidth())
        }
        PrimaryAction("繼續播放") { vm.chooseTab(1);vm.toggleIfPaused() }
    } else {
        EmptyCard("開始離線學習","先準備教材與離線語音包，就能開始播放。",Icons.Default.Headphones)
        PrimaryAction("建立本機字典或匯入教材") { nav.go("new") }
    }
    MenuLink("到期複習",ui.message("目前到期：")+due.due,Icons.Default.History) { nav.go("review-stats") }
    Actions {
        OutlinedButton(onClick={ vm.start(vm.settings.value.playback.copy(type="word")) }) { Text(ui(R.string.word_practice_1f71d6)) }
        OutlinedButton(onClick={ vm.start(vm.settings.value.playback.copy(type="sentence")) }) { Text(ui(R.string.sentence_listening_de9200)) }
        OutlinedButton(onClick={ vm.start(vm.settings.value.playback.copy(random=true)) }) { Text(ui(R.string.random_learning_f41522)) }
        OutlinedButton(onClick={ vm.start(vm.settings.value.playback.copy(state="learned",excludeLearned=false)) }) { Text(ui(R.string.review_learned_entries_96a409)) }
    }
    MenuLink("離線語音包","先檢查母語與目標語是否可離線使用",Icons.AutoMirrored.Filled.VolumeUp) { vm.checkVoices();nav.go("voices") }
}

@UnstableApi @Composable internal fun PlayerScreen(vm: ListenerViewModel) {
    val nav=LocalAppNavigator.current;val ui=UiStrings(LocalContext.current)
    val item by vm.item.collectAsStateWithLifecycle();val progress by vm.progress.collectAsStateWithLifecycle()
    val index by vm.index.collectAsStateWithLifecycle();val total by vm.queueSize.collectAsStateWithLifecycle()
    val playing by vm.playing.collectAsStateWithLifecycle();val buffering by vm.buffering.collectAsStateWithLifecycle()
    val position by vm.position.collectAsStateWithLifecycle();val duration by vm.duration.collectAsStateWithLifecycle()
    val saved by vm.session.collectAsStateWithLifecycle();val error by vm.playbackError.collectAsStateWithLifecycle()
    val sources by vm.sources.collectAsStateWithLifecycle()
    val config by vm.settings.collectAsStateWithLifecycle()
    val options=saved?.options ?: config.playback
    var more by remember { mutableStateOf(false) }
    val current=item
    if(current==null) {
        EmptyCard("還沒有播放場次","選擇已有的本機來源，或建立一份 AI 字典。",Icons.Default.Headphones)
        PrimaryAction("建立學習場次") { nav.go("session") };MenuLink("建立本機字典","使用 AI、CSV 或手動新增教材",Icons.Default.Add) { nav.go("new") }
    } else {
        Actions { StatusTag("本機語音播放");StatusTag("${index+1} / $total");StatusTag(sources.find { it.sourceId==current.sourceId }?.name ?: current.category,literal=true) }
        Panel("") {
            Text(options.native,style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
            Text(current.text(options.native),style=MaterialTheme.typography.headlineSmall)
            Text(options.target,style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
            Text(current.text(options.target),style=MaterialTheme.typography.headlineMedium,fontWeight=FontWeight.Bold,color=MaterialTheme.colorScheme.primary)
            if(current.pronunciationNote.isNotBlank()) Text(current.pronunciationNote,color=MaterialTheme.colorScheme.onSurfaceVariant)
            if(options.thirdLanguage) PlaybackOptions.LANGUAGES.filter { it!=options.native && it!=options.target && current.text(it).isNotBlank() }.forEach { Text(current.text(it),style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        error?.let { Panel(tr("播放需要處理")) { Text(ui.message(it),color=MaterialTheme.colorScheme.error);PrimaryAction("檢查離線語音包") { vm.checkVoices();nav.go("voices") } } }
        if(buffering) { LinearProgressIndicator(Modifier.fillMaxWidth());Text(ui(R.string.synthesizing_offline_audio_the_first_playback_ma_4a780a),style=MaterialTheme.typography.bodySmall) }
        Slider(value=if(duration>0) (position.toFloat()/duration).coerceIn(0f,1f) else 0f,onValueChange=vm::seek,enabled=duration>0)
        Text(ui(R.string.seconds_b14097,position/1000,duration/1000),style=MaterialTheme.typography.labelMedium)
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceEvenly,verticalAlignment=Alignment.CenterVertically) {
            IconButton(onClick=vm::previous,modifier=Modifier.size(56.dp)) { Icon(Icons.Default.SkipPrevious,ui(R.string.previous_entry_7f3fd8),Modifier.size(32.dp)) }
            FilledIconButton(onClick=vm::toggle,shape=CircleShape,modifier=Modifier.size(84.dp)) { Icon(if(playing) Icons.Default.Pause else Icons.Default.PlayArrow,if(playing) ui(R.string.pause_37676b) else ui(R.string.play_219253),Modifier.size(42.dp)) }
            IconButton(onClick=vm::next,modifier=Modifier.size(56.dp)) { Icon(Icons.Default.SkipNext,ui(R.string.next_entry_58bf90),Modifier.size(32.dp)) }
        }
        Actions {
            FilterChip(selected=progress?.favorite==true,onClick={ vm.mark("favorite") },label={ Text(ui(R.string.favorite_d07cee)) })
            FilterChip(selected=progress?.learned==true,onClick={ vm.mark(if(progress?.learned==true) "reset" else "learned") },label={ Text(ui(R.string.learned_0f130c)) })
            OutlinedButton(onClick=vm::next) { Text(ui(R.string.skip_this_time_9b9b43)) }
            OutlinedButton(onClick={ more=true }) { Text(tr("更多")) }
        }
        ReviewControls(vm)
        if(more) AlertDialog(onDismissRequest={ more=false },title={ Text(tr("教材學習動作")) },text={ Column {
            listOf("7" to ui(R.string.suspend_for_7_days_c6ba96),"30" to ui(R.string.suspend_for_30_days_31f064),"review" to if((progress?.reviewPriority ?: 0)>0) ui(R.string.remove_from_review_35169d) else ui(R.string.add_to_review_aec981),"reset" to ui(R.string.reset_to_unlearned_6ddb59)).forEach { (action,label) -> TextButton(onClick={ more=false;vm.mark(action) }) { Text(label) } }
        } },confirmButton={ TextButton(onClick={ more=false }) { Text(ui(R.string.cancel_4d0b46)) } })
    }
    MenuLink("建立學習場次","來源、語言、數量與進階篩選",Icons.Default.Tune) { nav.go("session") }
    if(total>0) MenuLink("目前播放清單",ui(R.string.entries_1b770f,"",index+1,total),Icons.AutoMirrored.Filled.QueueMusic) { nav.go("queue") }
}
