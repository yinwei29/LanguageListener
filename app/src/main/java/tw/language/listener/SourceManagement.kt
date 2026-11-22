@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package tw.language.listener

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import tw.language.listener.data.*
import tw.language.listener.documents.DocumentViewModel
import tw.language.listener.i18n.UiStrings

@UnstableApi @Composable
internal fun SourceManagement(source: Source,vm: ListenerViewModel,documents: DocumentViewModel=viewModel()) {
    val ui=UiStrings(LocalContext.current)
    val busy by vm.busy.collectAsStateWithLifecycle()
    val fileBusy by documents.busy.collectAsStateWithLifecycle()
    var rename by remember { mutableStateOf(false) }
    var name by rememberSaveable(source.sourceId,source.name) { mutableStateOf(source.name) }
    var editing by remember { mutableStateOf(false) }
    val local=source.access=="local" || source.sourceId==Repository.LOCAL_SOURCE_ID
    val export=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri -> uri?.let { documents.exportAndConnect(it,source.sourceId) } }
    Actions {
        Button(enabled=!busy,onClick={ vm.studySource(source.sourceId) }) { Text(ui.message("開始學習這個來源")) }
        TextButton(enabled=!busy,onClick={ rename=true }) { Text(ui.message("重新命名來源")) }
        if(local) {
            Button(enabled=!busy,onClick={ editing=true }) { Text(ui.message("新增此來源教材")) }
            OutlinedButton(enabled=!busy && !fileBusy,onClick={ export.launch("LanguageListener-lessons.csv") }) { Text(ui.message("另存並連接 CSV")) }
        }
    }
    if(editing) LocalLessonDialog(null,vm,source.sourceId) { editing=false }
    if(rename) AlertDialog(onDismissRequest={ rename=false },title={ Text(ui.message("重新命名來源")) },text={
        OutlinedTextField(name,{ name=it },label={ Text(ui.message("來源名稱")) },singleLine=true,modifier=Modifier.fillMaxWidth())
    },confirmButton={ TextButton(enabled=!busy && name.trim().isNotEmpty(),onClick={ vm.renameSource(source,name);rename=false }) { Text(ui.message("儲存名稱")) } },dismissButton={ TextButton(onClick={ rename=false }) { Text(ui(R.string.cancel_4d0b46)) } })
}
