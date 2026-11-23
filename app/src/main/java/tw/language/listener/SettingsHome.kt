package tw.language.listener

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import tw.language.listener.i18n.UiStrings

@UnstableApi @Composable internal fun SettingsHome(vm: ListenerViewModel) {
    val nav=LocalAppNavigator.current;val status by vm.voiceStatus.collectAsStateWithLifecycle()
    val options=vm.settings.collectAsStateWithLifecycle().value.playback
    StatusTag(if(options.languages().all { status[it]==true }) "目前語言的離線語音已就緒" else "先檢查離線語音包")
    MenuLink("離線語音包","檢查、安裝與語音引擎",Icons.AutoMirrored.Filled.VolumeUp) { vm.checkVoices();nav.go("voices") }
    MenuLink("備份與換機","完整教材與進度 ZIP",Icons.Default.Shield) { nav.go("backup") }
    MenuLink("語言與外觀","介面語言與深色模式",Icons.Default.DarkMode) { nav.go("appearance") }
    MenuLink("AI API 與模型","ChatGPT／Gemini／相容 API",Icons.Default.AutoAwesome) { nav.go("ai-settings") }
    MenuLink("同步與連線偏好","僅 Wi-Fi、啟動時同步與授權",Icons.Default.Sync) { nav.go("preferences") }
    Panel(tr("本機學習不需要 API 金鑰")) { Text(tr("AI 產生與雲端同步時才需要網路。")) }
}

@UnstableApi @Composable internal fun RestorePreview(vm: ListenerViewModel) {
    val preview by vm.preview.collectAsStateWithLifecycle();val busy by vm.busy.collectAsStateWithLifecycle()
    val ui=UiStrings(LocalContext.current)
    var replaceId by remember { mutableStateOf<Long?>(null) }
    preview?.let { pending ->
        AlertDialog(onDismissRequest={ if(!busy) vm.dismissPreview() },title={ Text(ui(R.string.import_preview_460dc3)) },text={
            Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                Text(ui(R.string.date_version_schema_lessons_learning_records_sou_f0b022,ui.date(pending.manifest.createdAt),pending.manifest.appVersion,pending.manifest.schemaVersion,pending.manifest.itemCount,pending.manifest.progressCount,pending.manifest.sourceCount))
                pending.sources.forEach { Text(it.name) }
                Text(ui(R.string.backup_replace_deletes_current_data_smart_merge_445fe0,if(pending.manifest.full) ui(R.string.full_6e6e58) else ui(R.string.learning_records_only_d16fb6)))
                if(!pending.manifest.full) Text(ui(R.string.lightweight_backups_exclude_lesson_text_after_re_c3f7cd))
            }
        },confirmButton={ TextButton(enabled=!busy,onClick={ vm.restore(false) }) { Text(ui(R.string.smart_merge_0494db)) } },dismissButton={ Column {
            TextButton(enabled=!busy,onClick={ replaceId=pending.manifest.createdAt }) { Text(ui(R.string.replace_all_720837)) }
            TextButton(enabled=!busy,onClick=vm::dismissPreview) { Text(ui(R.string.cancel_4d0b46)) }
        } })
        if(replaceId==pending.manifest.createdAt) AlertDialog(onDismissRequest={ replaceId=null },title={ Text(tr("完整覆蓋目前資料？")) },text={ Text(tr("將取代本機教材、進度與場次，並清除未審核草稿及檔案綁定。")) },confirmButton={ TextButton(enabled=!busy,onClick={ replaceId=null;vm.restore(true) }) { Text(tr("確認完整覆蓋"),color=MaterialTheme.colorScheme.error) } },dismissButton={ TextButton(onClick={ replaceId=null }) { Text(ui(R.string.cancel_4d0b46)) } })
    }
}
