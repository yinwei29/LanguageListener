package tw.language.listener

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable internal fun ModelPicker(value: String,choices: List<Pair<String,String>>,enabled: Boolean,onChange: (String)->Unit) {
    var open by remember { mutableStateOf(false) }
    var search by rememberSaveable { mutableStateOf("") }
    OutlinedButton(onClick={ open=true },enabled=enabled,modifier=Modifier.fillMaxWidth()) { Text(value) }
    if(open) AlertDialog(onDismissRequest={ open=false },title={ Text(tr("選擇模型")) },text={
        Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(search,{ search=it },label={ Text(tr("搜尋模型名稱或 ID")) },singleLine=true,modifier=Modifier.fillMaxWidth())
            LazyColumn(Modifier.heightIn(max=320.dp)) {
                items(choices.filter { it.first.contains(search,true) || it.second.contains(search,true) }.distinctBy { it.first },key={ it.first }) { (key,label) ->
                    TextButton(onClick={ onChange(key);open=false },modifier=Modifier.fillMaxWidth()) { Text(label) }
                }
            }
        }
    },confirmButton={ TextButton(onClick={ open=false }) { Text(tr("關閉")) } })
}
