@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package tw.language.listener

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import tw.language.listener.authoring.*
import tw.language.listener.data.*

@Composable
internal fun GoogleAuthoringPanel(report: (String)->Unit, vm: AuthoringViewModel=viewModel()) {
    val busy by vm.busy.collectAsStateWithLifecycle()
    val resolution by vm.resolution.collectAsStateWithLifecycle()
    val notice by vm.notice.collectAsStateWithLifecycle()
    val plan by vm.plan.collectAsStateWithLifecycle()
    val launcher=rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        vm.authorizationResult(if(result.resultCode==Activity.RESULT_OK) result.data else null)
    }
    LaunchedEffect(resolution) { resolution?.let { pending ->
        vm.launchedResolution()
        try { launcher.launch(IntentSenderRequest.Builder(pending.intentSender).build()) }
        catch(_: Exception) { report("無法開啟 Google 授權畫面，請確認 Google Play 服務"); vm.authorizationResult(null) }
    } }
    LaunchedEffect(notice) { notice?.let { report(it); vm.notice.value=null } }
    var name by rememberSaveable { mutableStateOf("") }
    var url by rememberSaveable { mutableStateOf("") }
    Panel("開始使用：連接與初始化 Sheets") {
        Text("貼上你可編輯的原始 Google Sheets 網址。授權後先檢查工作表，再確認補上教材欄位；不會清空既有資料。私人教材不需公開。")
        OutlinedTextField(name,{ name=it },label={ Text("教材來源名稱") },enabled=!busy,modifier=Modifier.fillMaxWidth())
        OutlinedTextField(url,{ url=it },label={ Text("Google Sheets 原始網址（可含 gid）") },enabled=!busy,modifier=Modifier.fillMaxWidth())
        Button(enabled=!busy && url.isNotBlank(),onClick={ vm.inspect(name,url) }) { Text("連接、儲存並初始化") }
        OutlinedButton(enabled=!busy && url.isNotBlank(),onClick={ vm.readOnly(name,url) }) { Text("已有教材：授權唯讀加入") }
        Text("唯讀加入要求現有教材欄位完整，不會初始化或修改工作表；之後確認寫回時才請求寫入權限。")
        Text("首次需 Google 帳號同意讀寫試算表。輸入連結本身不代表已取得該檔案的編輯權限。")
        TextButton(enabled=!busy,onClick=vm::clearGoogle) { Text("清除本機 Google 授權並停止授權同步") }
        if(busy) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            TextButton(onClick=vm::cancel) { Text("取消目前操作") }
        }
    }
    plan?.let { value -> AlertDialog(onDismissRequest=vm::dismissPlan,title={ Text("確認初始化教材欄位") },text={
        Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Text("目標：${value.name.ifBlank { value.title }}\n工作表：${value.title}\n${value.url}")
            Text(if(value.missing.isEmpty()) "需要的欄位已存在，只會儲存來源並同步。" else "只會補上以下缺少的欄位：\n${value.missing.joinToString(", ")}")
            Text("原有教材不會被刪除或覆寫。若已有教材列，每列仍需填寫穩定且唯一的 ID，否則同步會保留舊快取並報錯。")
        }
    },confirmButton={ TextButton(enabled=!busy,onClick={ vm.initialize(value) }) { Text("初始化並儲存") } },
        dismissButton={ TextButton(enabled=!busy,onClick=vm::dismissPlan) { Text("取消") } }) }
}

@Composable
internal fun AiAuthoringPanel(sources: List<Source>, vm: AuthoringViewModel=viewModel()) {
    val busy by vm.busy.collectAsStateWithLifecycle()
    val saved by vm.profile.collectAsStateWithLifecycle()
    val hasKey by vm.hasKey.collectAsStateWithLifecycle()
    val keySaved by vm.keySaved.collectAsStateWithLifecycle()
    val draft by vm.draft.collectAsStateWithLifecycle()
    var profile by remember(saved) { mutableStateOf(saved) }
    // API keys intentionally do not use rememberSaveable or a ViewModel saved-state bundle.
    var key by remember { mutableStateOf("") }
    LaunchedEffect(keySaved) { key="" }
    var sourceId by rememberSaveable { mutableStateOf("") }
    val writable=sources.filter { it.access=="google" }
    LaunchedEffect(writable) { if(writable.none { it.sourceId==sourceId }) sourceId=writable.firstOrNull()?.sourceId ?: "" }
    var topic by rememberSaveable { mutableStateOf("") }
    var category by rememberSaveable { mutableStateOf("日常") }
    var type by rememberSaveable { mutableStateOf("word") }
    var level by rememberSaveable { mutableStateOf("A1") }
    var count by rememberSaveable { mutableStateOf("20") }
    var languages by rememberSaveable { mutableStateOf("all") }
    var regenerate by remember { mutableStateOf(false) }
    fun generate() {
        val number=count.toIntOrNull()
        if(number==null || number !in 1..100) { vm.notice.value="單批數量請輸入 1–100"; return }
        vm.generate(AiRequest(sourceId,topic,category,type,level,number,when(languages) {
            "zh-en"->listOf("zh-TW","en-US"); "zh-id"->listOf("zh-TW","id-ID"); else->PlaybackOptions.LANGUAGES
        }))
    }
    Panel("AI 教材：設定與產生") {
        Text("使用你自己的 API 金鑰，費用依供應商計算。Gemini／ChatGPT 訂閱不代表 API 額度。生成內容尚未查證，本功能沒有網路搜尋，也不會自動寫回。")
        Choice("供應商",profile.provider,listOf("gemini" to "Gemini","openai" to "OpenAI","compatible" to "OpenAI 相容 API")) {
            profile=when(it) {
                "gemini"->AiProfile()
                "openai"->AiProfile("openai","https://api.openai.com/v1","gpt-4o-mini")
                else->AiProfile("compatible","https://api.openai.com/v1","")
            }
            key=""
        }
        OutlinedTextField(profile.endpoint,{ profile=profile.copy(endpoint=it) },label={ Text("API base URL（HTTPS，不含金鑰）") },enabled=!busy && profile.provider=="compatible",modifier=Modifier.fillMaxWidth())
        OutlinedTextField(profile.model,{ profile=profile.copy(model=it) },label={ Text("模型名稱") },enabled=!busy,modifier=Modifier.fillMaxWidth())
        OutlinedTextField(key,{ key=it },label={ Text("API 金鑰（留空沿用此供應商／網址的已存金鑰）") },visualTransformation=PasswordVisualTransformation(),keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Password,autoCorrectEnabled=false),enabled=!busy,singleLine=true,modifier=Modifier.fillMaxWidth())
        Text(if(hasKey) "目前已儲存金鑰：裝置加密，不納入備份。" else "目前尚未設定此供應商的金鑰。")
        Actions {
            Button(enabled=!busy,onClick={ vm.saveProfile(profile,key) }) { Text("儲存 AI 設定") }
            TextButton(enabled=!busy,onClick=vm::clearKey) { Text("移除目前已存金鑰") }
        }
        if(writable.isEmpty()) Text("請先在上方連接並初始化可寫入的 Sheets 來源。")
        else Choice("寫入來源",sourceId,writable.map { it.sourceId to it.name }) { sourceId=it }
        OutlinedTextField(topic,{ topic=it },label={ Text("主題，例如飯店入住常用詞彙") },enabled=!busy,modifier=Modifier.fillMaxWidth())
        OutlinedTextField(category,{ category=it },label={ Text("分類") },enabled=!busy,modifier=Modifier.fillMaxWidth())
        Choice("教材類型",type,listOf("word" to "單字","phrase" to "短語","sentence" to "句子")) { type=it }
        OutlinedTextField(level,{ level=it },label={ Text("難度，例如 A1、A2、B1") },enabled=!busy,modifier=Modifier.fillMaxWidth())
        Choice("語言",languages,listOf("all" to "繁中／英文／印尼文","zh-en" to "繁中＋英文","zh-id" to "繁中＋印尼文")) { languages=it }
        OutlinedTextField(count,{ count=it },label={ Text("單批數量 1–100（可分批加入）") },enabled=!busy,singleLine=true,modifier=Modifier.fillMaxWidth())
        if(profile!=saved) Text("請先儲存目前 AI 設定，再開始產生。")
        Button(enabled=!busy && hasKey && profile==saved && sourceId.isNotBlank() && topic.isNotBlank(),onClick={
            if(draft?.written==false) regenerate=true else generate()
        }) { Text("產生待審核教材") }
    }
    if(regenerate) AlertDialog(onDismissRequest={ regenerate=false },title={ Text("取代尚未寫入的草稿？") },
        text={ Text("成功產生新教材後，會取代目前待審核批次；現有 Sheets 與離線教材不變。") },
        confirmButton={ TextButton(onClick={ regenerate=false; generate() }) { Text("重新產生") } },
        dismissButton={ TextButton(onClick={ regenerate=false }) { Text("取消") } })
    draft?.let { DraftReview(it,sources,busy,vm::writeReviewed) }
}

@Composable
private fun DraftReview(draft: AiDraft, sources: List<Source>, busy: Boolean, write: (AiDraft,List<Item>)->Unit) {
    var edited by remember(draft.draftId,draft.written) { mutableStateOf(draft.items) }
    var selected by remember(draft.draftId,draft.written) { mutableStateOf(draft.selectedIds?.toSet() ?: emptySet()) }
    var page by remember(draft.draftId) { mutableStateOf(0) }
    var confirm by remember { mutableStateOf(false) }
    val source=sources.find { it.sourceId==draft.request.sourceId && it.access=="google" }
    Panel(if(draft.written) "本批寫回結果" else "人工審核：尚未寫回") {
        Text("主題：${draft.request.topic}\n來源：${source?.name ?: "來源已移除或尚未重新連接"}\n本批 ${draft.items.size} 筆，產生階段略過重複 ${draft.removedDuplicates} 筆")
        Text("AI 生成，未經網路搜尋或查證。請確認翻譯與用法後再寫入。未選項目視為本批不採用。")
        if(draft.written) Text("已確認新增 ${draft.writtenCount} 筆、已存在略過 ${draft.skippedCount} 筆；離線下載狀態請查看來源同步結果。")
        else {
            val pageItems=edited.withIndex().drop(page*10).take(10)
            Text("審核第 ${page+1}/${(edited.size+9)/10} 頁，每頁 10 筆；請逐筆或審核後選取本頁。")
            Actions {
                TextButton(enabled=!busy && page>0,onClick={ page-- }) { Text("上一頁") }
                TextButton(enabled=!busy && (page+1)*10<edited.size,onClick={ page++ }) { Text("下一頁") }
                TextButton(enabled=!busy,onClick={ selected=selected+pageItems.map { it.value.itemId } }) { Text("選取本頁") }
                TextButton(enabled=!busy,onClick={ selected=emptySet() }) { Text("取消所有選取") }
            }
            pageItems.forEach { (index,item) ->
                HorizontalDivider()
                Row { Checkbox(enabled=!busy,checked=item.itemId in selected,onCheckedChange={ checked -> selected=if(checked) selected+item.itemId else selected-item.itemId }); Text("第 ${index+1} 筆") }
                fun update(value: Item) { edited=edited.toMutableList().also { it[index]=value } }
                OutlinedTextField(item.zh,{ update(item.copy(zh=it)) },label={ Text("繁體中文") },enabled=!busy,modifier=Modifier.fillMaxWidth())
                OutlinedTextField(item.en,{ update(item.copy(en=it)) },label={ Text("English") },enabled=!busy,modifier=Modifier.fillMaxWidth())
                OutlinedTextField(item.id,{ update(item.copy(id=it)) },label={ Text("Bahasa Indonesia") },enabled=!busy,modifier=Modifier.fillMaxWidth())
                OutlinedTextField(item.pronunciationNote,{ update(item.copy(pronunciationNote=it)) },label={ Text("發音備註（選填）") },enabled=!busy,modifier=Modifier.fillMaxWidth())
            }
            Text("寫回會再次檢查 Sheets 的穩定 ID 與重複文字。若上一輪網路結果未確認，請先保留原文字重試。")
            Button(enabled=!busy && selected.isNotEmpty() && source!=null,onClick={ confirm=true }) { Text("確認寫回已選 ${selected.size} 筆") }
        }
    }
    if(confirm) AlertDialog(onDismissRequest={ confirm=false },title={ Text("寫入 Google Sheets？") },
        text={ Text("目標：${source?.name}\n${source?.url}\n將新增 ${selected.size} 筆已審核教材；不覆寫既有教材。寫入後依同步網路政策下載。") },
        confirmButton={ TextButton(enabled=!busy,onClick={ confirm=false; write(draft,edited.filter { it.itemId in selected }) }) { Text("確認新增") } },
        dismissButton={ TextButton(onClick={ confirm=false }) { Text("取消") } })
}
