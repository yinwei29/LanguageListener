package tw.language.listener

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import tw.language.listener.data.*
import tw.language.listener.i18n.UiStrings
import java.util.UUID

@UnstableApi
@Composable
internal fun LocalLessonsPanel(vm: ListenerViewModel) {
    val ui=UiStrings(LocalContext.current)
    var adding by rememberSaveable { mutableStateOf(false) }
    Panel(ui(R.string.local_lessons)) {
        Text(ui(R.string.local_lessons_help))
        Button(onClick={ adding=true }) { Text(ui(R.string.add_lesson)) }
    }
    if(adding) LocalLessonDialog(null,vm) { adding=false }
}

@UnstableApi
@Composable
internal fun LocalLessonDialog(original: Item?, vm: ListenerViewModel, sourceId: String=Repository.LOCAL_SOURCE_ID, close: ()->Unit) {
    val ui=UiStrings(LocalContext.current)
    val busy by vm.busy.collectAsStateWithLifecycle()
    val itemId=rememberSaveable(original?.itemId) { original?.itemId ?: UUID.randomUUID().toString() }
    var zh by rememberSaveable(itemId) { mutableStateOf(original?.zh ?: "") }
    var en by rememberSaveable(itemId) { mutableStateOf(original?.en ?: "") }
    var id by rememberSaveable(itemId) { mutableStateOf(original?.id ?: "") }
    var ja by rememberSaveable(itemId) { mutableStateOf(original?.ja ?: "") }
    var ko by rememberSaveable(itemId) { mutableStateOf(original?.ko ?: "") }
    var category by rememberSaveable(itemId) { mutableStateOf(original?.category ?: "日常") }
    var type by rememberSaveable(itemId) { mutableStateOf(original?.type ?: "word") }
    var level by rememberSaveable(itemId) { mutableStateOf(original?.level ?: "") }
    val enoughLanguages=listOf(zh,en,id,ja,ko).count { it.isNotBlank() }>=2
    val fieldsFit=listOf(zh,en,id,ja,ko).all { it.length<=100_000 } && category.length<=512 && level.length<=512
    val valid=enoughLanguages && fieldsFit
    AlertDialog(onDismissRequest={ if(!busy) close() },title={ Text(ui(if(original==null) R.string.add_lesson else R.string.edit_local_lesson)) },
        text={ Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Text(ui(R.string.local_lessons_help))
            OutlinedTextField(zh,{ zh=it },label={ Text("繁體中文") },enabled=!busy,modifier=Modifier.fillMaxWidth())
            OutlinedTextField(en,{ en=it },label={ Text("English") },enabled=!busy,modifier=Modifier.fillMaxWidth())
            OutlinedTextField(id,{ id=it },label={ Text("Bahasa Indonesia") },enabled=!busy,modifier=Modifier.fillMaxWidth())
            OutlinedTextField(ja,{ ja=it },label={ Text("日本語") },enabled=!busy,modifier=Modifier.fillMaxWidth())
            OutlinedTextField(ko,{ ko=it },label={ Text("한국어") },enabled=!busy,modifier=Modifier.fillMaxWidth())
            OutlinedTextField(category,{ category=it },label={ Text(ui(R.string.category_7c6fd7)) },enabled=!busy,modifier=Modifier.fillMaxWidth())
            Choice(ui(R.string.lesson_type_f0ab97),type,listOf("word" to ui(R.string.words_d34b56),"phrase" to ui(R.string.phrases_155650),"sentence" to ui(R.string.sentences_8cfcfc))) { if(!busy) type=it }
            OutlinedTextField(level,{ level=it },label={ Text(ui(R.string.level_e_g_a1_a2_b1_95dafc)) },enabled=!busy,modifier=Modifier.fillMaxWidth())
            if(!fieldsFit) Text(ui(R.string.lesson_fields_too_long),color=MaterialTheme.colorScheme.error)
            if(!enoughLanguages) Text(ui(R.string.two_languages_required),color=MaterialTheme.colorScheme.error)
        } },confirmButton={ TextButton(enabled=valid && !busy,onClick={
            val value=(original ?: Item(sourceId,itemId,zh="",en="",id="")).copy(zh=zh,en=en,id=id,category=category,type=type,level=level,ja=ja,ko=ko)
            vm.saveLocalItem(value,close)
        }) { Text(ui(R.string.save_lesson)) } },
        dismissButton={ TextButton(enabled=!busy,onClick=close) { Text(ui(R.string.cancel_4d0b46)) } })
}
