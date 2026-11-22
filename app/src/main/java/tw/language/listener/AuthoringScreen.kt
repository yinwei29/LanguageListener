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
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import tw.language.listener.authoring.*
import tw.language.listener.data.*

@Composable
internal fun GoogleAuthoringPanel(report: (String)->Unit, vm: AuthoringViewModel=viewModel()) {
    val ui=tw.language.listener.i18n.UiStrings(androidx.compose.ui.platform.LocalContext.current)
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
    Panel(ui(R.string.get_started_connect_and_initialize_sheets_14dccc)) {
        Text(ui(R.string.paste_the_original_google_sheets_url_you_can_edi_3cc076))
        OutlinedTextField(name,{ name=it },label={ Text(ui(R.string.lesson_source_name_6b80fc)) },enabled=!busy,modifier=Modifier.fillMaxWidth())
        OutlinedTextField(url,{ url=it },label={ Text(ui(R.string.original_google_sheets_url_gid_supported_8056ed)) },enabled=!busy,modifier=Modifier.fillMaxWidth())
        Button(enabled=!busy && url.isNotBlank(),onClick={ vm.inspect(name,url) }) { Text(ui(R.string.connect_save_and_initialize_4ed786)) }
        OutlinedButton(enabled=!busy && url.isNotBlank(),onClick={ vm.readOnly(name,url) }) { Text(ui(R.string.existing_lessons_connect_read_only_cf42a3)) }
        Text(ui(R.string.read_only_connection_requires_complete_lesson_co_de5e25))
        Text(ui(R.string.google_account_consent_is_required_for_reading_a_a9889c))
        TextButton(enabled=!busy,onClick=vm::clearGoogle) { Text(ui(R.string.clear_local_google_authorization_and_stop_author_5b0551)) }
        if(busy) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            TextButton(onClick=vm::cancel) { Text(ui(R.string.cancel_current_operation_bb77ad)) }
        }
    }
    plan?.let { value -> AlertDialog(onDismissRequest=vm::dismissPlan,title={ Text(ui(R.string.confirm_lesson_column_initialization_d37eb0)) },text={
        Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Text(ui(R.string.destination_sheet_8a2797, (value.name.ifBlank { value.title }), (value.title), (value.url)))
            Text(if(value.missing.isEmpty()) ui(R.string.required_columns_already_exist_only_the_source_w_cab59e) else ui(R.string.only_these_missing_columns_will_be_added_b39fb4, (value.missing.joinToString(", "))))
            Text(ui(R.string.existing_lessons_will_not_be_deleted_or_overwrit_c49191))
        }
    },confirmButton={ TextButton(enabled=!busy,onClick={ vm.initialize(value) }) { Text(ui(R.string.initialize_and_save_97d8ee)) } },
        dismissButton={ TextButton(enabled=!busy,onClick=vm::dismissPlan) { Text(ui(R.string.cancel_4d0b46)) } }) }
}

@Composable
internal fun AiAuthoringPanel(sources: List<Source>,onStudy: (AiDraft)->Unit,onVoices: ()->Unit, vm: AuthoringViewModel=viewModel()) {
    val ui=tw.language.listener.i18n.UiStrings(androidx.compose.ui.platform.LocalContext.current)
    val busy by vm.busy.collectAsStateWithLifecycle()
    val saved by vm.profile.collectAsStateWithLifecycle()
    val hasKey by vm.hasKey.collectAsStateWithLifecycle()
    val keySaved by vm.keySaved.collectAsStateWithLifecycle()
    val modelList by vm.models.collectAsStateWithLifecycle()
    val aiError by vm.aiError.collectAsStateWithLifecycle()
    val generationProgress by vm.generationProgress.collectAsStateWithLifecycle()
    val draft by vm.draft.collectAsStateWithLifecycle()
    var showSettings by rememberSaveable { mutableStateOf(true) }
    LaunchedEffect(hasKey,keySaved) { if(hasKey) showSettings=false }
    var profile by remember(saved) { mutableStateOf(saved) }
    // API keys intentionally do not use rememberSaveable or a ViewModel saved-state bundle.
    var key by remember { mutableStateOf("") }
    LaunchedEffect(keySaved) { key="" }
    var newSource by rememberSaveable { mutableStateOf(true) }
    var sourceName by rememberSaveable { mutableStateOf("") }
    var sourceId by rememberSaveable { mutableStateOf("") }
    val writable=sources.filter { it.access in listOf("google","file","local") || it.sourceId==Repository.LOCAL_SOURCE_ID }
    LaunchedEffect(writable) { if(writable.none { it.sourceId==sourceId }) sourceId=writable.firstOrNull()?.sourceId ?: "" }
    var topic by rememberSaveable { mutableStateOf("") }
    var category by rememberSaveable { mutableStateOf("日常") }
    var type by rememberSaveable { mutableStateOf("word") }
    var level by rememberSaveable { mutableStateOf("A1") }
    var count by rememberSaveable { mutableStateOf("20") }
    var languages by rememberSaveable { mutableStateOf("all") }
    var customLanguages by rememberSaveable { mutableStateOf(PlaybackOptions.CORE_LANGUAGES) }
    var regenerate by remember { mutableStateOf(false) }
    fun generate() {
        val number=count.toIntOrNull()
        if(number==null || number !in 1..100) { vm.notice.value="單批數量請輸入 1–100"; return }
        val request=AiRequest(if(newSource) "new-source" else sourceId,topic,category,type,level,number,when(languages) {
            "zh-en"->listOf("zh-TW","en-US"); "zh-id"->listOf("zh-TW","id-ID");"zh-ja"->listOf("zh-TW","ja-JP");"zh-ko"->listOf("zh-TW","ko-KR");"ja-ko"->listOf("ja-JP","ko-KR");"five"->PlaybackOptions.LANGUAGES;"custom"->PlaybackOptions.LANGUAGES.filter { it in customLanguages };else->PlaybackOptions.CORE_LANGUAGES
        })
        if(newSource) vm.generateNewSource(sourceName,request) else vm.generate(request)
    }
    Panel(ui.message("AI 新增來源與教材")) {
        Text(ui.message("選 ChatGPT／OpenAI API 並儲存金鑰與模型，輸入主題產生草稿；審核後才建立本機來源，可直接離線學習。"))
        Choice(ui.message("新增方式"),if(newSource) "new" else "existing",listOf("new" to ui.message("用 AI 建立新來源"),"existing" to ui.message("加入既有來源"))) { if(!busy) newSource=it=="new" }
        if(newSource) OutlinedTextField(sourceName,{ sourceName=it },label={ Text(ui.message("新來源名稱，例如日文旅遊單字")) },enabled=!busy,modifier=Modifier.fillMaxWidth())
        Text(ui(R.string.use_your_own_api_key_your_provider_charges_for_u_2160a9))
        TextButton(onClick={ showSettings=!showSettings }) { Text(ui.message("顯示／收起 AI 設定")) }
        Text((when(profile.provider) { "openai"->"ChatGPT／OpenAI API";"gemini"->"Gemini";else->ui(R.string.openai_compatible_api_fd90fc) })+" · "+profile.model)
        if(showSettings) {
        Choice(ui(R.string.provider_46ea7f),profile.provider,listOf("gemini" to "Gemini","openai" to "ChatGPT／OpenAI API","compatible" to ui(R.string.openai_compatible_api_fd90fc))) {
            if(!busy) { profile=when(it) {
                "gemini"->AiProfile()
                "openai"->AiProfile("openai","https://api.openai.com/v1","gpt-4.1-mini",outputMode="schema")
                else->AiProfile("compatible","https://api.openai.com/v1","")
            }
            key="" }
        }
        OutlinedTextField(profile.endpoint,{ key="";profile=profile.copy(endpoint=it) },label={ Text(ui(R.string.api_base_url_https_no_key_228df9)) },enabled=!busy && profile.provider=="compatible",modifier=Modifier.fillMaxWidth())
        if(profile.provider!="gemini") Choice(ui.message("API 格式"),profile.protocol,listOf("auto" to ui.message("自動（OpenAI 使用 Responses；相容 API 使用 Chat）"),"responses" to "Responses API","chat" to "Chat Completions")) { if(!busy) profile=profile.copy(protocol=it) }
        Choice(ui.message("輸出格式"),profile.outputMode,listOf("json" to "JSON","schema" to "JSON Schema","prompt" to ui.message("僅提示詞 JSON（模型不支援格式參數時使用）"))) { if(!busy) profile=profile.copy(outputMode=it) }
        val remoteModels=modelList?.takeIf { it.origin==profile.keySlot() }?.items
        val modelChoices=(remoteModels ?: AiModels.examples(profile.provider)).map { it.id to it.label }
        if(modelChoices.isNotEmpty()) Choice(ui.message("模型選單"),profile.model,modelChoices) { if(!busy) profile=profile.copy(model=it) }
        Text(if(remoteModels==null) ui.message("目前顯示模型範例，不代表帳號可用；請載入模型清單或輸入自己的模型 ID。") else ui.message("清單由供應商依此金鑰回傳；列出模型不保證可用額度或支援所有輸出格式。"))
        OutlinedTextField(profile.model,{ profile=profile.copy(model=it) },label={ Text(ui(R.string.model_name_4585e1)) },enabled=!busy,modifier=Modifier.fillMaxWidth())
        OutlinedTextField(key,{ key=it;vm.models.value=null },label={ Text(ui(R.string.api_key_leave_blank_to_reuse_the_saved_key_for_t_fab1c0)) },visualTransformation=PasswordVisualTransformation(),keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Password,autoCorrectEnabled=false),enabled=!busy,singleLine=true,modifier=Modifier.fillMaxWidth())
        Text(if(hasKey) ui(R.string.key_saved_encrypted_on_this_device_and_excluded_65497f) else ui(R.string.no_key_is_configured_for_this_provider_yet_cf1a46))
        Actions {
            OutlinedButton(enabled=!busy,onClick={ vm.loadModels(profile,key) }) { Text(ui.message("載入模型清單／檢查金鑰")) }
            Button(enabled=!busy,onClick={ vm.saveProfile(profile,key) }) { Text(ui(R.string.save_ai_settings_70dc45)) }
            TextButton(enabled=!busy,onClick=vm::clearKey) { Text(ui(R.string.remove_the_saved_key_e3ab12)) }
        }
        }
        if(!newSource && writable.isEmpty()) Text(ui.message("沒有可加入的來源；請切換成用 AI 建立新來源。"))
        else if(!newSource) Choice(ui(R.string.destination_source_e40e7f),sourceId,writable.map { it.sourceId to it.name }) { sourceId=it }
        OutlinedTextField(topic,{ topic=it },label={ Text(ui(R.string.topic_e_g_hotel_check_in_vocabulary_586e44)) },enabled=!busy,modifier=Modifier.fillMaxWidth())
        OutlinedTextField(category,{ category=it },label={ Text(ui(R.string.category_7c6fd7)) },enabled=!busy,modifier=Modifier.fillMaxWidth())
        Choice(ui(R.string.lesson_type_f0ab97),type,listOf("word" to ui(R.string.words_d34b56),"phrase" to ui(R.string.phrases_155650),"sentence" to ui(R.string.sentences_8cfcfc))) { type=it }
        OutlinedTextField(level,{ level=it },label={ Text(ui(R.string.level_e_g_a1_a2_b1_95dafc)) },enabled=!busy,modifier=Modifier.fillMaxWidth())
        Choice(ui(R.string.language_78be3c),languages,listOf("all" to ui(R.string.traditional_chinese_english_indonesian_e2e999),"zh-en" to ui(R.string.traditional_chinese_english_c3b3d3),"zh-id" to ui(R.string.traditional_chinese_indonesian_b57055),"zh-ja" to ui.message("繁中／日文"),"zh-ko" to ui.message("繁中／韓文"),"ja-ko" to ui.message("日文／韓文"),"five" to ui.message("繁中／英文／印尼文／日文／韓文"),"custom" to ui.message("自訂語言（至少兩種）"))) { if(!busy) languages=it }
        if(languages=="custom") {
            Actions { PlaybackOptions.LANGUAGE_LABELS.forEach { (tag,label) -> FilterChip(selected=tag in customLanguages,enabled=!busy,onClick={ customLanguages=if(tag in customLanguages) customLanguages-tag else customLanguages+tag },label={ Text(label) }) } }
            if(customLanguages.size<2) Text(ui.message("請至少選擇兩種語言"),color=MaterialTheme.colorScheme.error)
        }
        OutlinedTextField(count,{ count=it },label={ Text(ui(R.string.entries_per_batch_1_100_more_batches_can_be_adde_e59fc7)) },enabled=!busy,singleLine=true,modifier=Modifier.fillMaxWidth())
        Text(ui.message("每次 API 請求最多 10 筆；100 筆會分批產生並逐批保存。失敗或取消後可繼續已保存草稿，API 可能按請求計費。"))
        generationProgress?.let { (done,total) -> Text(ui.message("已完成的請求量：")+"$done / $total"); LinearProgressIndicator(Modifier.fillMaxWidth()) }
        if(busy && generationProgress!=null) TextButton(onClick=vm::cancel) { Text(ui.message("停止產生並保留草稿")) }
        aiError?.let { Text(ui.message(it),color=MaterialTheme.colorScheme.error) }
        if(draft?.generationComplete==false && draft?.written==false) {
            Text(ui.message("分批產生尚未完成；已保存內容可先審核，或繼續剩餘批次。"))
            Button(enabled=!busy && hasKey && profile==saved && draft?.generationProfile==saved,onClick=vm::resumeGeneration) { Text(ui.message("繼續產生剩餘批次")) }
        }
        if(profile!=saved) Text(ui(R.string.save_the_current_ai_settings_before_generating_00bd57))
        Button(enabled=!busy && hasKey && profile==saved && (if(newSource) sourceName.trim().isNotEmpty() else sourceId.isNotBlank()) && topic.isNotBlank() && (languages!="custom" || customLanguages.size>=2),onClick={
            if(draft?.written==false) regenerate=true else generate()
        }) { Text(ui(R.string.generate_a_review_draft_526d40)) }
    }
    if(regenerate) AlertDialog(onDismissRequest={ regenerate=false },title={ Text(ui(R.string.replace_the_unwritten_draft_e61ef4)) },
        text={ Text(ui(R.string.a_successfully_generated_batch_will_replace_the_3db38e)) },
        confirmButton={ TextButton(onClick={ regenerate=false; generate() }) { Text(ui(R.string.generate_again_2f2f5e)) } },
        dismissButton={ TextButton(onClick={ regenerate=false }) { Text(ui(R.string.cancel_4d0b46)) } })
    draft?.let { if(it.items.isNotEmpty()) DraftReview(it,sources,busy,vm::writeReviewed,onStudy,onVoices) else Text(ui.message("目前沒有新的待審核教材；請繼續批次或更換主題。")) }
}

@Composable
private fun DraftReview(draft: AiDraft, sources: List<Source>, busy: Boolean, write: (AiDraft,List<Item>)->Unit,onStudy: (AiDraft)->Unit,onVoices: ()->Unit) {
    val ui=tw.language.listener.i18n.UiStrings(androidx.compose.ui.platform.LocalContext.current)
    var edited by remember(draft.draftId,draft.written,draft.generatedCount) { mutableStateOf(draft.items) }
    var selected by remember(draft.draftId,draft.written,draft.generatedCount) { mutableStateOf(draft.selectedIds?.toSet() ?: emptySet()) }
    var page by remember(draft.draftId) { mutableStateOf(0) }
    var confirm by remember { mutableStateOf(false) }
    val source=sources.find { it.sourceId==draft.request.sourceId } ?: draft.pendingSource?.takeIf { !draft.written }
    val local=source?.access in listOf("file","local") || source?.sourceId==Repository.LOCAL_SOURCE_ID
    Panel(if(draft.written) ui(R.string.batch_write_result_025023) else ui(R.string.review_not_yet_written_0c4c42)) {
        Text(ui(R.string.topic_source_this_batch_entries_duplicates_remov_e9c7b2, (draft.request.topic), (source?.name ?: ui(R.string.source_removed_or_not_reconnected_880136)), (draft.items.size), (draft.removedDuplicates)))
        Text(ui(R.string.ai_generated_without_web_search_or_fact_checking_78767f))
        if(draft.written) {
            Button(enabled=source!=null,onClick={ onStudy(draft) }) { Text(ui.message("開始學習這個來源")) }
            TextButton(onClick=onVoices) { Text(ui.message("檢查／安裝離線語音包")) }
            Text(if(local) ui.message("已儲存到 App 的教材筆數：")+draft.writtenCount else ui(R.string.confirmed_new_entries_already_present_and_skippe_76c2bd, (draft.writtenCount), (draft.skippedCount)))
        } else {
            val pageItems=edited.withIndex().drop(page*10).take(10)
            Text(ui(R.string.review_page_10_entries_per_page_select_entries_i_6510f0, (page+1), ((edited.size+9)/10)))
            Actions {
                TextButton(enabled=!busy && page>0,onClick={ page-- }) { Text(ui(R.string.previous_page_d1bf40)) }
                TextButton(enabled=!busy && (page+1)*10<edited.size,onClick={ page++ }) { Text(ui(R.string.next_page_d44baa)) }
                TextButton(enabled=!busy,onClick={ selected=selected+pageItems.map { it.value.itemId } }) { Text(ui(R.string.select_this_page_cbccfc)) }
                TextButton(enabled=!busy,onClick={ selected=emptySet() }) { Text(ui(R.string.deselect_all_63dd30)) }
            }
            pageItems.forEach { (index,item) ->
                HorizontalDivider()
                Row { Checkbox(modifier=Modifier.semantics { contentDescription=ui(R.string.select_lesson_f118b9,index+1) },enabled=!busy,checked=item.itemId in selected,onCheckedChange={ checked -> selected=if(checked) selected+item.itemId else selected-item.itemId }); Text(ui(R.string.entry_749064, (index+1))) }
                fun update(value: Item) { edited=edited.toMutableList().also { it[index]=value } }
                OutlinedTextField(item.zh,{ update(item.copy(zh=it)) },label={ Text("繁體中文") },enabled=!busy,modifier=Modifier.fillMaxWidth())
                OutlinedTextField(item.en,{ update(item.copy(en=it)) },label={ Text("English") },enabled=!busy,modifier=Modifier.fillMaxWidth())
                OutlinedTextField(item.id,{ update(item.copy(id=it)) },label={ Text("Bahasa Indonesia") },enabled=!busy,modifier=Modifier.fillMaxWidth())
                OutlinedTextField(item.ja,{ update(item.copy(ja=it)) },label={ Text("日本語") },enabled=!busy,modifier=Modifier.fillMaxWidth())
                OutlinedTextField(item.ko,{ update(item.copy(ko=it)) },label={ Text("한국어") },enabled=!busy,modifier=Modifier.fillMaxWidth())
                OutlinedTextField(item.pronunciationNote,{ update(item.copy(pronunciationNote=it)) },label={ Text(ui(R.string.pronunciation_note_optional_8c42ba)) },enabled=!busy,modifier=Modifier.fillMaxWidth())
            }
            Text(ui(R.string.writing_rechecks_stable_ids_and_duplicate_text_i_3cb7d4))
            Button(enabled=!busy && selected.isNotEmpty() && source!=null,onClick={ confirm=true }) { Text(ui(R.string.confirm_writing_selected_entries_df53ff, (selected.size))) }
        }
    }
    if(confirm) AlertDialog(onDismissRequest={ confirm=false },title={ Text(if(draft.pendingSource!=null) ui.message("建立來源並儲存審核教材") else if(local) ui.message("儲存審核教材到 App") else ui(R.string.write_to_google_sheets_ce59c6)) },
        text={ Text(if(local) ui.message("確認儲存選取的教材到 App；檔案與 Sheets 請使用檔案同步按鈕寫回。") else ui(R.string.destination_add_reviewed_entries_without_overwri_04cd4d, (source?.name), (source?.url), (selected.size))) },
        confirmButton={ TextButton(enabled=!busy,onClick={ confirm=false; write(draft,edited.filter { it.itemId in selected }) }) { Text(ui(R.string.confirm_addition_f2a275)) } },
        dismissButton={ TextButton(onClick={ confirm=false }) { Text(ui(R.string.cancel_4d0b46)) } })
}
