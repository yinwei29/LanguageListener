package tw.language.listener.data

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

object Migrations {
    val V4_TO_V5=object: Migration(4,5) {
        override fun migrate(db: SupportSQLiteDatabase) {
            for((name,type,value) in listOf(Triple("nextReviewAt","INTEGER","0"),Triple("lastReviewedAt","INTEGER","0"),Triple("intervalDays","INTEGER","0"),Triple("ease","REAL","2.5"),Triple("repetitions","INTEGER","0"),Triple("lapses","INTEGER","0")))
                db.execSQL("ALTER TABLE progress ADD COLUMN $name $type NOT NULL DEFAULT $value")
            db.execSQL("CREATE TABLE IF NOT EXISTS review_events (eventId TEXT NOT NULL,sourceId TEXT NOT NULL,itemId TEXT NOT NULL,reviewedAt INTEGER NOT NULL,epochDay INTEGER NOT NULL,rating TEXT NOT NULL,PRIMARY KEY(eventId))")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_review_events_epochDay ON review_events(epochDay)")
        }
    }
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
