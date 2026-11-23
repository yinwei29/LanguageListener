@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package tw.language.listener

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import tw.language.listener.data.*

@Composable
internal fun Choice(label: String, value: String, choices: List<Pair<String,String>>, onChange: (String)->Unit) {
    val ui=tw.language.listener.i18n.UiStrings(androidx.compose.ui.platform.LocalContext.current)
    var open by remember { mutableStateOf(false) }
    Column {
        Text(label,style=MaterialTheme.typography.labelLarge)
        Box {
            OutlinedButton(onClick={ open=true },modifier=Modifier.fillMaxWidth()) { Text(choices.firstOrNull { it.first==value }?.second ?: value.ifBlank { ui(R.string.all_778fc8) }) }
            DropdownMenu(expanded=open,onDismissRequest={ open=false }) {
                choices.forEach { (key,name) -> DropdownMenuItem(text={ Text(name) },onClick={ onChange(key); open=false }) }
            }
        }
    }
}

@Composable
internal fun Toggle(label: String, checked: Boolean, onChange: (Boolean)->Unit) {
    val ui=tw.language.listener.i18n.UiStrings(androidx.compose.ui.platform.LocalContext.current)
    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.SpaceBetween) {
        Text(label,Modifier.weight(1f)); Switch(checked=checked,onCheckedChange=onChange)
    }
}

@Composable
internal fun OptionsEditor(saved: PlaybackOptions, onSave: (PlaybackOptions)->Unit, onStart: (PlaybackOptions)->Unit,sourceChoices: List<Source>,section: String="session") {
    val ui=tw.language.listener.i18n.UiStrings(androidx.compose.ui.platform.LocalContext.current)
    val setup: PlaybackSetup=androidx.lifecycle.viewmodel.compose.viewModel()
    if(!setup.initialized) { setup.optionState.value=saved;setup.countState.value=if(saved.count==0) "全部" else saved.count.toString();setup.initialized=true }
    var options by setup.optionState
    var count by setup.countState
    var error by setup.errorState
    fun validated(action: (PlaybackOptions)->Unit) {
        try {
            val amount=if(count=="全部") 0 else requireNotNull(count.toIntOrNull()) { "請輸入正整數或「全部」" }.also { require(it>0) { "自訂數量需為正整數" } }
            val value=options.copy(count=amount); value.validate(); error=null; action(value)
        } catch(e: Exception) { error=e.message ?: "設定不合法" }
    }
    Panel(ui(R.string.session_and_speech_settings_8673cd)) {
        Text(ui(R.string.changes_below_apply_when_creating_the_next_sessi_20ffb6))
        val languages=PlaybackOptions.LANGUAGE_LABELS
        if(section=="session") {
        Choice(ui.message("教材來源"),options.sourceId,listOf("" to ui(R.string.all_778fc8))+sourceChoices.map { it.sourceId to it.name }) { options=options.copy(sourceId=it) }
        Choice(ui(R.string.native_language_9bbc85),options.native,languages) { options=options.copy(native=it) }
        Choice(ui(R.string.target_language_651506),options.target,languages) { options=options.copy(target=it) }
        }
        if(section=="speech") {
        Choice(ui(R.string.speech_order_cfb84b),options.direction,listOf("native-target","target-native","target").map { it to directionLabel(it,ui) }) { options=options.copy(direction=it) }
        Toggle(ui.message("顯示其他語言"),options.thirdLanguage) { options=options.copy(thirdLanguage=it) }
        }
        if(section=="session") {
        Choice(ui(R.string.session_size_666f5b),count,listOf("20","50","100","全部").map { it to if(it=="全部") ui(R.string.all_778fc8) else it }) { count=it }
        OutlinedTextField(value=if(count=="全部") ui(R.string.all_778fc8) else count,onValueChange={ count=if(it==ui(R.string.all_778fc8)) "全部" else it },label={ Text(ui(R.string.custom_positive_integer_or_all_7a1c42)) },singleLine=true,modifier=Modifier.fillMaxWidth())
        Toggle(ui(R.string.shuffle_no_repeats_within_a_session_61e644),options.random) { options=options.copy(random=it) }
        Toggle(ui(R.string.exclude_learned_entries_61e4c7),options.excludeLearned) { options=options.copy(excludeLearned=it) }
        }
        if(section=="filters") {
        Toggle(ui(R.string.prioritize_review_363ac5),options.reviewFirst) { options=options.copy(reviewFirst=it) }
        OutlinedTextField(options.category,{ options=options.copy(category=it) },label={ Text(ui(R.string.category_blank_for_all_33af15)) },modifier=Modifier.fillMaxWidth())
        Choice(ui(R.string.lesson_type_f0ab97),options.type,listOf("" to ui(R.string.all_778fc8),"word" to ui(R.string.words_d34b56),"phrase" to ui(R.string.phrases_155650),"sentence" to ui(R.string.sentences_8cfcfc))) { options=options.copy(type=it) }
        OutlinedTextField(options.level,{ options=options.copy(level=it) },label={ Text(ui(R.string.level_blank_for_all_f722d1)) },modifier=Modifier.fillMaxWidth())
        Choice(ui(R.string.status_452935),options.state,listOf("all" to ui(R.string.all_778fc8),"learned" to ui(R.string.learned_0f130c),"favorite" to ui(R.string.favorite_d07cee),"review" to ui(R.string.needs_review_d00608),"due" to ui.message("到期複習"))) { options=options.copy(state=it,excludeLearned=if(it in listOf("learned","due")) false else options.excludeLearned) }
        Choice(ui(R.string.avoid_recently_played_entries_b9e9d4),options.avoidRecentDays.toString(),listOf("0" to ui(R.string.do_not_exclude_e78264),"1" to ui(R.string.last_day_1442e5),"7" to ui(R.string.last_7_days_2a0a05),"30" to ui(R.string.last_30_days_279e4c))) { options=options.copy(avoidRecentDays=it.toInt()) }
        }
        if(section=="speech") {
        Toggle(ui(R.string.loop_session_366b56),options.loop) { options=options.copy(loop=it) }
        languages.forEach { (tag,label) ->
            Text(ui(R.string.speech_rate_0959eb, (label), (ui.decimal(options.rate(tag)))))
            Slider(value=options.rate(tag),valueRange=.25f..2f,onValueChange={ value -> options=when(tag) {
                "zh-TW"->options.copy(zhRate=value); "en-US"->options.copy(enRate=value); "ja-JP"->options.copy(jaRate=value);"ko-KR"->options.copy(koRate=value);else->options.copy(idRate=value)
            } })
        }
        Text(ui(R.string.repeat_each_entry_times_4a864c, (options.repeats)))
        Slider(value=options.repeats.toFloat(),valueRange=1f..10f,steps=8,onValueChange={ options=options.copy(repeats=it.toInt()) })
        Text(ui(R.string.gap_between_segments_seconds_6afbef, (options.gapSeconds)))
        Slider(value=options.gapSeconds.toFloat(),valueRange=0f..30f,steps=29,onValueChange={ options=options.copy(gapSeconds=it.toInt()) })
        }
        error?.let { Text(ui.message(it),color=MaterialTheme.colorScheme.error) }
        Actions {
            OutlinedButton(onClick={ validated(onSave) }) { Text(ui(R.string.save_settings_bcc070)) }
            if(section=="session") Button(onClick={ validated(onStart) }) { Text(ui(R.string.create_new_session_b359ba)) }
        }
    }
}


internal class PlaybackSetup: androidx.lifecycle.ViewModel() {
    var initialized=false
    val optionState=mutableStateOf(PlaybackOptions())
    val countState=mutableStateOf("20")
    val errorState=mutableStateOf<String?>(null)
}
