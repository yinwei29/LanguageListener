package tw.language.listener

import android.app.Application
import androidx.room.Room
import tw.language.listener.data.ListenerDatabase
import tw.language.listener.data.Repository

open class ListenerApp : Application() {
    val vault by lazy { tw.language.listener.authoring.DeviceVault(this) }
    val google by lazy { tw.language.listener.authoring.GoogleAuthorization(this,vault) }
    val sheets by lazy { tw.language.listener.authoring.GoogleSheets() }
    val dictionaries by lazy { tw.language.listener.dictionary.OfflineDictionary(repository,java.io.File(filesDir,"dictionaries")) }
    val speech by lazy { tw.language.listener.playback.OfflineSpeech(this) }
    val repository by lazy {
        Repository(Room.databaseBuilder(this, ListenerDatabase::class.java, "listener.db").addMigrations(tw.language.listener.data.Migrations.V1_TO_V2,tw.language.listener.data.Migrations.V2_TO_V3,tw.language.listener.data.Migrations.V3_TO_V4,tw.language.listener.data.Migrations.V4_TO_V5,tw.language.listener.data.Migrations.V5_TO_V6).build())
    }
}
