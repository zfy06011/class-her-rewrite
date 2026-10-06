package com.classher.timetable.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE identities ADD COLUMN colorSlot INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE school_baselines ADD COLUMN timeMode TEXT NOT NULL DEFAULT 'periods'")
        db.execSQL("ALTER TABLE school_baselines ADD COLUMN customStart TEXT")
        db.execSQL("ALTER TABLE school_baselines ADD COLUMN customEnd TEXT")
        db.execSQL("ALTER TABLE projections ADD COLUMN timeMode TEXT NOT NULL DEFAULT 'periods'")
        db.execSQL("ALTER TABLE projections ADD COLUMN customStart TEXT")
        db.execSQL("ALTER TABLE projections ADD COLUMN customEnd TEXT")
        db.execSQL("CREATE TABLE IF NOT EXISTS manual_arrangements (id TEXT NOT NULL, name TEXT NOT NULL, teacher TEXT NOT NULL, room TEXT NOT NULL, weekday INTEGER NOT NULL, weeks TEXT NOT NULL, periods TEXT NOT NULL, timeMode TEXT NOT NULL DEFAULT 'periods', customStart TEXT, customEnd TEXT, PRIMARY KEY(id), FOREIGN KEY(id) REFERENCES identities(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
        db.execSQL("INSERT INTO manual_arrangements (id, name, teacher, room, weekday, weeks, periods, timeMode, customStart, customEnd) SELECT p.id, p.name, p.teacher, p.room, p.weekday, p.weeks, p.periods, p.timeMode, p.customStart, p.customEnd FROM projections p INNER JOIN identities i ON p.id = i.id WHERE i.origin = 'manual'")
    }
}

val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS school_reviews (id TEXT NOT NULL, termId TEXT NOT NULL, fetchedAt INTEGER NOT NULL, payload TEXT NOT NULL, PRIMARY KEY(id), FOREIGN KEY(termId) REFERENCES terms(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_school_reviews_termId ON school_reviews (termId)")
    }
}
