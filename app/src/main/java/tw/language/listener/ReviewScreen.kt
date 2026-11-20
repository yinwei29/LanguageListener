@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package tw.language.listener

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import tw.language.listener.data.Progress
import tw.language.listener.i18n.UiStrings
import tw.language.listener.review.*
import java.time.*
import java.time.format.DateTimeFormatter

@UnstableApi @Composable
internal fun ReviewDashboard(vm: ListenerViewModel) {
    val ui=UiStrings(LocalContext.current)
    val due by vm.due.collectAsStateWithLifecycle()
    val days by vm.reviewDays.collectAsStateWithLifecycle()
    val now by vm.reviewClock.collectAsStateWithLifecycle()
    var expanded by rememberSaveable { mutableStateOf(false) }
    Panel(ui.message("到期複習")) {
        Text(ui.message("已安排複習：")+due.scheduled+" · "+ui.message("目前到期：")+due.due)
        Text(ui.message("評分只調整複習排程，不改變已學會、收藏或暫停期限。複習場次依目前語言篩選，包含已學會的到期教材。"))
        Button(enabled=due.due>0,onClick=vm::startDue) { Text(ui.message("開始到期複習")) }
        if(due.scheduled==0) Text(ui.message("先在播放器用重學／困難／普通／容易評分，建立第一筆複習排程。"))
        Text(ui.message("最近七天評分：")+days.sumOf { it.total }+" · "+ui.message("重學次數：")+days.sumOf { it.again })
        TextButton(onClick={ expanded=!expanded }) { Text(ui.message("顯示／收起七天紀錄")) }
        if(expanded) {
            val today=Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).toLocalDate()
            for(offset in 6 downTo 0) {
                val day=today.minusDays(offset.toLong());val record=days.find { it.epochDay==day.toEpochDay() }
                Text(day.format(DateTimeFormatter.ofPattern("MM/dd"))+" · "+ui.message("評分：")+(record?.total ?: 0)+" · "+ui.message("重學：")+(record?.again ?: 0))
            }
        }
    }
}

@UnstableApi @Composable
internal fun ReviewControls(vm: ListenerViewModel) {
    val ui=UiStrings(LocalContext.current)
    val item by vm.item.collectAsStateWithLifecycle()
    val progress by vm.progress.collectAsStateWithLifecycle()
    val recorded by vm.reviewRecorded.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val now by vm.reviewClock.collectAsStateWithLifecycle()
    var reset by remember { mutableStateOf<tw.language.listener.data.Item?>(null) }
    val current=item ?: return
    val state=progress ?: Progress(current.sourceId,current.itemId)
    Panel(ui.message("記憶評分與間隔複習")) {
        Text(ui.message("選擇記憶程度以安排下次複習；評分後仍可繼續聽目前教材。"))
        Actions {
            for(rating in Rating.entries) {
                val next=ReviewScheduler.schedule(state,rating,now)
                val label=when(rating) { Rating.AGAIN->"重學";Rating.HARD->"困難";Rating.GOOD->"普通";Rating.EASY->"容易" }
                OutlinedButton(enabled=!busy && !recorded,onClick={ vm.rate(rating,current.sourceId,current.itemId) }) {
                    Text(ui.message(label)+" · "+if(next.intervalDays==0) ui.message("10 分鐘") else next.intervalDays.toString()+" "+ui.message("天"))
                }
            }
        }
        if(recorded) Text(ui.message("此筆已評分；切換到其他教材後可繼續評分。"))
        if(state.lastReviewedAt>0) {
            Text(ui.message("下次複習：")+ui.date(state.nextReviewAt))
            TextButton(enabled=!busy,onClick={ reset=current }) { Text(ui.message("清除此筆複習排程")) }
        }
    }
    reset?.let { target -> AlertDialog(onDismissRequest={ reset=null },title={ Text(ui.message("清除此筆複習排程")) },text={ Column { Text(target.zh+"\n"+target.en);Text(ui.message("只清除排程；保留已學會、收藏、暫停期限、播放次數與歷史評分。")) } },confirmButton={ TextButton(enabled=!busy,onClick={ reset=null;vm.clearReview(target.sourceId,target.itemId) }) { Text(ui.message("確認清除排程")) } },dismissButton={ TextButton(onClick={ reset=null }) { Text(ui(R.string.cancel_4d0b46)) } }) }
}
