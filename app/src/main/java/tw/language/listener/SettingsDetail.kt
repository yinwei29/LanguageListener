@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package tw.language.listener

import android.content.Intent
import android.speech.tts.TextToSpeech
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import tw.language.listener.data.*

@UnstableApi @Composable
internal fun SettingsDetail(vm: ListenerViewModel,section: String) {
    val ui=tw.language.listener.i18n.UiStrings(androidx.compose.ui.platform.LocalContext.current)
    val settings by vm.settings.collectAsStateWithLifecycle()
    val status by vm.voiceStatus.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val context=LocalContext.current
    val nav=LocalAppNavigator.current
    var full by rememberSaveable { mutableStateOf(true) }
    var pendingFull by rememberSaveable { mutableStateOf(true) }
    val export=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri -> uri?.let { vm.export(it,pendingFull) } }
    val import=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(vm::import) }
    if(section=="backup") Panel(ui(R.string.backup_and_device_transfer_f1a679)) {
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
    if(section=="voices") Panel(ui(R.string.offline_voice_packages_8ee771)) {
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
    if(section=="preferences") MenuLink("Google 授權與來源","查看來源或重新連接 Google Sheets",Icons.Default.Cloud) { nav.go("google") }
    if(section in listOf("appearance","preferences")) Panel(tr(if(section=="appearance") "語言與外觀" else "同步與連線偏好")) {
        if(section=="appearance") Toggle(ui(R.string.dark_mode_750642),settings.dark) { vm.saveSettings(settings.copy(dark=it)) }
        if(section=="preferences") Toggle(ui(R.string.sync_on_launch_b1fb8c),settings.syncOnLaunch) { vm.saveSettings(settings.copy(syncOnLaunch=it)) }
        if(section=="preferences") Toggle(ui(R.string.sync_only_on_wi_fi_bdf678),settings.wifiOnly) { vm.saveSettings(settings.copy(wifiOnly=it)) }
        if(section=="preferences") Text(ui(R.string.changing_the_network_policy_cancels_old_work_and_1b3e74))
        if(section=="appearance") Choice(ui(R.string.interface_language_c5354a),settings.uiLanguage,listOf("zh-TW" to "繁體中文","en" to "English","id" to "Bahasa Indonesia")) { vm.saveSettings(settings.copy(uiLanguage=it)) }
        if(section=="appearance") Text(ui(R.string.changing_the_interface_language_does_not_change_06a194))
    }
}
