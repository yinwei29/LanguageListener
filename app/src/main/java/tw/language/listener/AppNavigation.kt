package tw.language.listener

import android.net.Uri
import androidx.compose.runtime.*

/** Saved string entries survive process recreation; source names never become route delimiters. */
internal data class AppNavigator(val open: (String,String)->Unit,val back: ()->Unit) {
    fun go(page: String,source: String="")=open(page,source)
}
internal val LocalAppNavigator=staticCompositionLocalOf<AppNavigator> { error("Navigator not installed") }
internal object AppRoutes {
    fun entry(page: String,source: String="")=page+"/"+Uri.encode(source)
    fun page(entry: String)=entry.substringBefore('/')
    fun source(entry: String)=Uri.decode(entry.substringAfter('/',""))
    fun title(page: String)=when(page) {
        "new"->"新增來源或教材";"files"->"本機 Excel／CSV";"google"->"連接 Google Sheets";"ai"->"用 AI 建立字典";"ai-settings"->"AI API 與模型";"review"->"審核與建立字典";"source"->"教材來源";"browse"->"所有教材";"session"->"建立學習場次";"filters"->"進階篩選";"speech"->"朗讀設定";"queue"->"目前播放清單";"review-stats"->"複習與學習紀錄";"voices"->"離線語音包";"backup"->"備份與換機";"appearance"->"語言與外觀";"preferences"->"同步與連線偏好";"dictionary"->"CSV 字典檔";"restored"->"還原完成";else->"Language Listener"
    }
}
