package tw.language.listener

import android.app.Application
import androidx.room.Room
import tw.language.listener.data.ListenerDatabase
import tw.language.listener.data.Repository

class ListenerApp : Application() {
    val speech by lazy { tw.language.listener.playback.OfflineSpeech(this) }
    val repository by lazy {
        Repository(Room.databaseBuilder(this, ListenerDatabase::class.java, "listener.db").build())
    }
}
