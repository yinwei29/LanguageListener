package tw.language.listener

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import tw.language.listener.authoring.AuthoringViewModel
import tw.language.listener.documents.DocumentViewModel

/** Activity-level effects keep consent and results working on every nested screen. */
@Composable
internal fun ServiceEffects(report: (String)->Unit,auth: AuthoringViewModel=viewModel(),documents: DocumentViewModel=viewModel()) {
    val resolution by auth.resolution.collectAsStateWithLifecycle()
    val notice by auth.notice.collectAsStateWithLifecycle()
    val fileNotice by documents.notice.collectAsStateWithLifecycle()
    val launcher=rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result -> auth.authorizationResult(if(result.resultCode==Activity.RESULT_OK) result.data else null) }
    LaunchedEffect(resolution) { resolution?.let {
        auth.launchedResolution()
        try { launcher.launch(IntentSenderRequest.Builder(it.intentSender).build()) }
        catch(_: Exception) { report("無法開啟 Google 授權畫面，請確認 Google Play 服務");auth.authorizationResult(null) }
    } }
    LaunchedEffect(notice) { notice?.let { report(it);auth.notice.value=null } }
    LaunchedEffect(fileNotice) { fileNotice?.let { report(it);documents.notice.value=null } }
}
