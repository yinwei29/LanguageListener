package tw.language.listener.i18n

import android.content.Context
import androidx.annotation.StringRes
import java.text.DateFormat
import java.text.NumberFormat
import java.util.Date

/** Translate app-owned messages at presentation; never translate lesson content. */
class UiStrings(private val context: Context) {
    operator fun invoke(@StringRes id: Int, vararg args: Any?): String = context.getString(id,*args)
    private val locale get()=context.resources.configuration.locales[0]
    fun date(value: Long): String=DateFormat.getDateTimeInstance(DateFormat.DEFAULT,DateFormat.DEFAULT,locale).format(Date(value))
    fun decimal(value: Float): String=NumberFormat.getNumberInstance(locale).apply {
        minimumFractionDigits=2; maximumFractionDigits=2
    }.format(value)
    fun message(raw: String): String {
        if(raw=="CSV 為空") return invoke(tw.language.listener.R.string.empty_sheet_help)
        MessageCatalog.entries[raw]?.let { return invoke(it) }
        for(template in templates) {
            val match=template.pattern.matchEntire(raw) ?: continue
            val args=match.groupValues.drop(1).toTypedArray()
            // These two templates contain a controlled API service name, not user content.
            if(template.serviceArgument && args.firstOrNull() in setOf("AI 產生教材","AI 模型清單"))
                args[0]=invoke(checkNotNull(MessageCatalog.entries[args[0]]))
            return invoke(template.id,*args)
        }
        if(raw.contains('\n')) return raw.lines().joinToString("\n") { message(it) }
        return raw
    }
    private data class Template(val id: Int,val pattern: Regex,val serviceArgument: Boolean)
    companion object {
        private val placeholder=Regex("%[1-9][0-9]*\\\$s")
        private val templates=MessageCatalog.entries.mapNotNull { (source,id) ->
            val matches=placeholder.findAll(source).toList()
            if(matches.isEmpty()) null else {
                var end=0
                val pattern=buildString {
                    for(match in matches) {
                        append(Regex.escape(source.substring(end,match.range.first)))
                        append("([\\s\\S]*?)")
                        end=match.range.last+1
                    }
                    append(Regex.escape(source.substring(end)))
                }
                Template(id,Regex(pattern),source.startsWith("%1\$s：HTTP") || source.startsWith("%1\$s 回應過大"))
            }
        }
    }
}
