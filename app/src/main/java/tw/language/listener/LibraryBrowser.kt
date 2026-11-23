package tw.language.listener

import android.app.Application
import androidx.lifecycle.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import tw.language.listener.data.Item

/** Independent, cancellable reads do not get dropped when playback/settings perform a mutation. */
class LibraryBrowser(app: Application): AndroidViewModel(app) {
    private val dao=(app as ListenerApp).repository.dao
    val items=MutableStateFlow<List<Item>>(emptyList())
    val more=MutableStateFlow(false)
    val busy=MutableStateFlow(false)
    val error=MutableStateFlow<String?>(null)
    private var job: Job?=null
    private var generation=0
    private var request=Triple("","","")
    fun load(source: String,query: String,category: String,append: Boolean=false) {
        val key=Triple(source,query,category)
        if(append && (busy.value || key!=request)) return
        job?.cancel();request=key;val id=++generation
        if(!append) { items.value=emptyList();more.value=false }
        job=viewModelScope.launch {
            busy.value=true;error.value=null
            try {
                val page=dao.searchSource(source,query,category,100,if(append) items.value.size else 0)
                ensureActive();items.value=if(append) items.value+page else page;more.value=page.size==100
            } catch(e: CancellationException) { throw e }
            catch(_: Exception) { error.value="無法讀取本機教材，請重新搜尋" }
            finally { if(generation==id) busy.value=false }
        }
    }
}
