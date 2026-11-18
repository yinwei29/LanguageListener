@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package tw.language.listener

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.util.UnstableApi
import tw.language.listener.authoring.AuthoringViewModel
import tw.language.listener.data.*
import tw.language.listener.documents.*
import tw.language.listener.i18n.UiStrings

@UnstableApi @Composable
internal fun DocumentPanel(listener: ListenerViewModel,report: (String)->Unit,vm: DocumentViewModel=viewModel(),auth: AuthoringViewModel=viewModel()) {
    val ui=UiStrings(LocalContext.current)
    val sources by vm.sources.collectAsStateWithLifecycle()
    val documents by vm.documents.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val authBusy by auth.busy.collectAsStateWithLifecycle()
    val notice by vm.notice.collectAsStateWithLifecycle()
    val plan by vm.plan.collectAsStateWithLifecycle()
    val cloudHeaders by vm.cloudInitialization.collectAsStateWithLifecycle()
    val cloud by vm.cloudPlan.collectAsStateWithLifecycle()
    var target by rememberSaveable { mutableStateOf("") }
    var exportId by rememberSaveable { mutableStateOf("") }
    var recoveryId by rememberSaveable { mutableStateOf("") }
    var adding by remember { mutableStateOf<Source?>(null) }
    val pick=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { vm.inspect(it,target.ifEmpty { null }) } }
    val create=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri -> uri?.let { vm.create(it,target.ifEmpty { null }) } }
    val export=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri -> uri?.let { vm.export(it,exportId) } }
    val recover=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri -> uri?.let { vm.recover(it,recoveryId) } }
    LaunchedEffect(notice) { notice?.let { report(it);vm.notice.value=null } }
    val active=busy || authBusy
    Panel(ui.message("本機 Excel／CSV：離線開始")) {
        Text(ui.message("先建立 CSV 或選擇 .xlsx／UTF-8 CSV，再預覽初始化。檔案由你選擇儲存位置，不需 Google 授權。"))
        Choice(ui.message("接到既有來源（重新連接或共用 Sheets 進度）"),target,
            listOf("" to ui.message("建立新來源"))+sources.filter { it.sourceId !in listOf("demo",Repository.LOCAL_SOURCE_ID) }.map { it.sourceId to it.name }) { if(!active) target=it }
        Actions {
            Button(enabled=!active,onClick={ create.launch("LanguageListener-lessons.csv") }) { Text(ui.message("建立初始 CSV")) }
            OutlinedButton(enabled=!active,onClick={ pick.launch(arrayOf("text/csv","text/comma-separated-values","application/vnd.openxmlformats-officedocument.spreadsheetml.sheet","application/vnd.ms-excel","application/octet-stream")) }) { Text(ui.message("選擇 Excel／CSV")) }
        }
        Text(ui.message("支援 .xlsx 與 UTF-8 CSV；.xls／巨集檔請先轉存。初始化只補教材欄位與缺少的 ID，保留其他欄位及工作表。"))
        Text(ui.message("同步不依缺少的列自動刪除教材；停用請設定 enabled=false。兩邊同時修改同一 ID 時會停止，不直接覆蓋。"))
        if(active) LinearProgressIndicator(Modifier.fillMaxWidth())
        sources.filter { it.access=="file" }.forEach { source ->
            key(source.sourceId) {
                HorizontalDivider();Text(source.name,style=MaterialTheme.typography.titleMedium)
                val binding=documents.firstOrNull { it.sourceId==source.sourceId }
                Text(if(binding==null) ui.message("尚未連接檔案；請選擇此來源後重新選檔") else ui.message("檔案權限保存在此裝置；換機後須重新選檔"))
                var url by rememberSaveable(source.sourceId,source.url) { mutableStateOf(source.url) }
                Actions {
                    Button(enabled=!active,onClick={ adding=source }) { Text(ui.message("新增此來源教材")) }
                    OutlinedButton(enabled=!active && binding!=null,onClick={ vm.syncFile(source) }) { Text(ui.message("同步 App 與本機檔案")) }
                    TextButton(enabled=!active,onClick={ exportId=source.sourceId;export.launch("LanguageListener-export.csv") }) { Text(ui.message("另存 App 教材為 CSV")) }
                    TextButton(enabled=!active,onClick={ recoveryId=source.sourceId;recover.launch(if(binding?.sheet?.isNotBlank()==true) "LanguageListener-recovery.xlsx" else "LanguageListener-recovery.csv") }) { Text(ui.message("另存同步前的復原副本")) }
                }
                OutlinedTextField(url,{ url=it },enabled=!active,label={ Text(ui.message("選用：原本的 Google Sheets 網址")) },modifier=Modifier.fillMaxWidth())
                Button(enabled=!active && binding!=null && url.isNotBlank(),onClick={ vm.previewCloud(source,url,auth) }) { Text(ui.message("預覽本機與 Google Sheets 同步")) }
            }
        }
    }
    adding?.let { source -> LocalLessonDialog(null,listener,source.sourceId) { adding=null } }
    plan?.let { value -> AlertDialog(onDismissRequest={ if(!active) vm.plan.value=null },title={ Text(ui.message("確認檔案初始化與連接")) },text={
        Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Text(value.source.name)
            if(value.table.sheets.isNotEmpty()) Choice(ui.message("Excel 工作表"),value.table.sheet,value.table.sheets.map { it.value to it.key }) { sheet -> vm.inspect(android.net.Uri.parse(value.binding.uri),target.ifEmpty { null },sheet) }
            Text(ui.message("將補上的欄位：")+value.missing.joinToString(", "))
            Text(ui.message("將補上穩定 ID 的筆數：")+value.generatedIds)
            Text(ui.message("連接後的教材筆數：")+value.merged.size)
            Text(ui.message("確認後會把必要欄位、ID 與合併教材寫回選取的檔案。原有學習進度保留。"))
        }
    },confirmButton={ TextButton(enabled=!active,onClick={ vm.initialize(value) }) { Text(ui.message("初始化並連接")) } },dismissButton={ TextButton(enabled=!active,onClick={ vm.plan.value=null }) { Text(ui(R.string.cancel_4d0b46)) } }) }
    cloudHeaders?.let { value -> AlertDialog(onDismissRequest={ if(!active) vm.cloudInitialization.value=null },title={ Text(ui.message("確認初始化雲端缺少欄位")) },text={
        Column { Text(value.second.title);Text(value.second.url);Text(ui.message("將補上的欄位：")+value.second.missing.joinToString(", "));Text(ui.message("只補缺少欄位，不新增重複來源；完成後重新預覽同步。")) }
    },confirmButton={ TextButton(enabled=!active,onClick={ vm.initializeCloud(value,auth) }) { Text(ui.message("初始化雲端欄位")) } },dismissButton={ TextButton(enabled=!active,onClick={ vm.cloudInitialization.value=null }) { Text(ui(R.string.cancel_4d0b46)) } }) }
    cloud?.let { value -> AlertDialog(onDismissRequest={ if(!active) vm.cloudPlan.value=null },title={ Text(ui.message("確認同步本機與 Google Sheets")) },text={
        Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Text(value.file.source.name);Text(value.sheet.title);Text(value.sheet.url)
            Text(ui.message("同步後的教材筆數：")+value.merged.size)
            if(value.snapshot==null) Text(ui.message("這是空白工作表，確認後會先初始化教材欄位。"))
            Text(ui.message("確認後依穩定 ID 合併並寫回 App、本機檔案與 Google Sheets。雲端需 Google 授權及網路；離線仍可使用本機教材。"))
        }
    },confirmButton={ TextButton(enabled=!active,onClick={ vm.commitCloud(value,auth) }) { Text(ui.message("確認同步")) } },dismissButton={ TextButton(enabled=!active,onClick={ vm.cloudPlan.value=null }) { Text(ui(R.string.cancel_4d0b46)) } }) }
}
