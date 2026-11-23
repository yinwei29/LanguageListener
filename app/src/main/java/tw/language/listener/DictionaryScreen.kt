package tw.language.listener

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import tw.language.listener.documents.DocumentViewModel

@Composable internal fun DictionaryActions(sourceId: String,documents: DocumentViewModel=viewModel()) {
    val sources by documents.sources.collectAsStateWithLifecycle();val busy by documents.busy.collectAsStateWithLifecycle()
    val file by documents.dictionary.collectAsStateWithLifecycle()
    val source=sources.find { it.sourceId==sourceId }
    var connect by rememberSaveable(sourceId) { mutableStateOf(true) }
    val canConnect=source?.access=="local" || source?.sourceId==tw.language.listener.data.Repository.LOCAL_SOURCE_ID
    val save=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri -> uri?.let { if(canConnect && connect) documents.exportAndConnect(it,sourceId) else documents.export(it,sourceId) } }
    LaunchedEffect(sourceId) { documents.loadDictionary(sourceId) }
    if(source==null) return
    Panel(tr("本機 CSV 字典檔")) {
        val current=file?.takeIf { it.sourceId==sourceId }
        if(current!=null) {
            StatusTag("字典檔已保存在 App")
            Text(tr("教材筆數：")+current.entries)
            Text("CSV · UTF-8 · "+current.bytes+" bytes")
            Text(tr("含五種語言欄位、穩定教材 ID 與分類，可用 Excel 開啟。"))
        } else Text(tr("可從已審核的本機教材建立 CSV，不需要網路或 Google 授權。"))
        Text(tr("修改或同步教材後，可按更新字典檔重新保存。"),style=MaterialTheme.typography.bodySmall)
        if(busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        PrimaryAction("建立／更新字典檔",!busy) { documents.buildDictionary(sourceId) }
        if(canConnect) Toggle(tr("另存後連接為本機檔案來源"),connect) { connect=it }
        PrimaryAction("儲存字典檔到手機",!busy) {
            val filename=source.name.replace(Regex("[^\\p{L}\\p{N}._-]+"),"-").take(60).ifBlank { "LanguageListener" }
            save.launch("$filename.csv")
        }
        Text(tr("取消儲存位置選擇不影響 App 中的字典與教材。完整 ZIP 可保存教材與學習進度。"),style=MaterialTheme.typography.bodySmall)
    }
}
