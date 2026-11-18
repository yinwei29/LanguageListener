package tw.language.listener.data

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

object Migrations {
    val V3_TO_V4=object: Migration(3,4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("CREATE TABLE IF NOT EXISTS local_documents (sourceId TEXT NOT NULL, uri TEXT NOT NULL, sheet TEXT NOT NULL, fileHash TEXT NOT NULL, fileBase TEXT NOT NULL, cloudBase TEXT NOT NULL, PRIMARY KEY(sourceId))")
        }
    }
    val V2_TO_V3=object: Migration(2,3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE settings ADD COLUMN uiLanguage TEXT NOT NULL DEFAULT 'zh-TW'")
        }
    }
    val V1_TO_V2=object: Migration(1,2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE sources ADD COLUMN access TEXT NOT NULL DEFAULT 'public'")
            db.execSQL("ALTER TABLE settings ADD COLUMN aiProfile TEXT")
            db.execSQL("CREATE TABLE IF NOT EXISTS ai_drafts (singleton INTEGER NOT NULL, payload TEXT NOT NULL, PRIMARY KEY(singleton))")
        }
    }
}
