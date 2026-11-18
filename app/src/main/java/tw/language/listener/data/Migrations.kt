package tw.language.listener.data

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

object Migrations {
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
