package com.trackmaster.app.data.database

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.trackmaster.app.utils.AppLog

/**
 * 数据库管理器 — 持有 SQLiteOpenHelper 实例，管理 schema 创建和迁移。
 * DAO 类通过构造函数注入 DatabaseManager 来获取数据库连接。
 */
class DatabaseManager(context: android.content.Context) : SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    companion object {
        const val DATABASE_NAME = "geofence.db"
        const val DATABASE_VERSION = 7

        // 围栏表
        const val TABLE_FENCES = "fences"
        const val KEY_ID = "id"
        const val KEY_NAME = "name"
        const val KEY_TYPE = "type"
        const val KEY_CENTER_LAT = "center_lat"
        const val KEY_CENTER_LNG = "center_lng"
        const val KEY_RADIUS = "radius"
        const val KEY_POINTS = "points"
        const val KEY_ENABLED = "enabled"
        const val KEY_CREATED_AT = "created_at"
        const val KEY_CATEGORY_ID = "category_id"
        const val KEY_COLOR = "color"
        const val KEY_ALPHA = "alpha"

        // 分类表
        const val TABLE_CATEGORIES = "categories"
        const val KEY_CATEGORY_TABLE_ID = "id"
        const val KEY_CATEGORY_NAME = "name"
        const val KEY_CATEGORY_ICON = "icon"
        const val KEY_CATEGORY_SORT_ORDER = "sort_order"
        const val KEY_CATEGORY_CREATED_AT = "created_at"

        // 事件表
        const val TABLE_EVENTS = "events"
        const val KEY_EVENT_ID = "event_id"
        const val KEY_FENCE_ID = "fence_id"
        const val KEY_FENCE_NAME = "fence_name"
        const val KEY_ACTION = "action"
        const val KEY_TIMESTAMP = "timestamp"
        const val KEY_LATITUDE = "latitude"
        const val KEY_LONGITUDE = "longitude"
        const val KEY_ACCURACY = "accuracy"

        // 停留记录表
        const val TABLE_STAYS = "stays"
        const val KEY_STAY_ID = "stay_id"
        const val KEY_ENTER_TIME = "enter_time"
        const val KEY_EXIT_TIME = "exit_time"
        const val KEY_DURATION = "duration"

        // 按日停留记录表
        const val TABLE_DAILY_STAYS = "daily_stays"
        const val KEY_DAILY_DATE = "daily_date"
        const val KEY_DAILY_FENCE_ID = "daily_fence_id"
        const val KEY_DAILY_FENCE_NAME = "daily_fence_name"
        const val KEY_DAILY_DURATION = "daily_duration"
        const val KEY_DAILY_LAST_UPDATED = "daily_last_updated"

        // 导出元数据表
        const val TABLE_EXPORT_META = "export_meta"
        const val KEY_EXPORT_ID = "export_id"
        const val KEY_EXPORT_TYPE = "export_type"
        const val KEY_EXPORT_FILENAME = "filename"
        const val KEY_EXPORT_RECORD_COUNT = "record_count"
        const val KEY_EXPORT_TIMESTAMP = "export_timestamp"
    }

    override fun onOpen(db: SQLiteDatabase) {
        super.onOpen(db)
        db.execSQL("PRAGMA foreign_keys = ON")
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE $TABLE_FENCES (
                $KEY_ID TEXT PRIMARY KEY,
                $KEY_NAME TEXT NOT NULL,
                $KEY_TYPE TEXT NOT NULL,
                $KEY_CENTER_LAT REAL,
                $KEY_CENTER_LNG REAL,
                $KEY_RADIUS REAL,
                $KEY_POINTS TEXT,
                $KEY_ENABLED INTEGER DEFAULT 1,
                $KEY_CREATED_AT INTEGER NOT NULL,
                $KEY_CATEGORY_ID TEXT DEFAULT 'unassigned',
                $KEY_COLOR TEXT DEFAULT '#FF0000',
                $KEY_ALPHA REAL DEFAULT 0.3
            )
        """.trimIndent())

        db.execSQL("""
            CREATE TABLE $TABLE_CATEGORIES (
                $KEY_CATEGORY_TABLE_ID TEXT PRIMARY KEY,
                $KEY_CATEGORY_NAME TEXT NOT NULL UNIQUE,
                $KEY_CATEGORY_ICON TEXT,
                $KEY_CATEGORY_SORT_ORDER INTEGER DEFAULT 0,
                $KEY_CATEGORY_CREATED_AT INTEGER NOT NULL
            )
        """.trimIndent())

        db.execSQL("""
            CREATE TABLE $TABLE_EVENTS (
                $KEY_EVENT_ID TEXT PRIMARY KEY,
                $KEY_FENCE_ID TEXT NOT NULL,
                $KEY_FENCE_NAME TEXT NOT NULL,
                $KEY_ACTION TEXT NOT NULL,
                $KEY_TIMESTAMP INTEGER NOT NULL,
                $KEY_LATITUDE REAL NOT NULL,
                $KEY_LONGITUDE REAL NOT NULL,
                $KEY_ACCURACY REAL NOT NULL,
                FOREIGN KEY($KEY_FENCE_ID) REFERENCES $TABLE_FENCES($KEY_ID) ON DELETE CASCADE
            )
        """.trimIndent())

        db.execSQL("""
            CREATE TABLE $TABLE_STAYS (
                $KEY_STAY_ID TEXT PRIMARY KEY,
                $KEY_FENCE_ID TEXT NOT NULL,
                $KEY_FENCE_NAME TEXT NOT NULL,
                $KEY_ENTER_TIME INTEGER NOT NULL,
                $KEY_EXIT_TIME INTEGER,
                $KEY_DURATION INTEGER,
                FOREIGN KEY($KEY_FENCE_ID) REFERENCES $TABLE_FENCES($KEY_ID) ON DELETE CASCADE
            )
        """.trimIndent())

        db.execSQL("""
            CREATE TABLE $TABLE_DAILY_STAYS (
                $KEY_DAILY_DATE TEXT NOT NULL,
                $KEY_DAILY_FENCE_ID TEXT NOT NULL,
                $KEY_DAILY_FENCE_NAME TEXT NOT NULL,
                $KEY_DAILY_DURATION INTEGER NOT NULL DEFAULT 0,
                $KEY_DAILY_LAST_UPDATED INTEGER NOT NULL,
                PRIMARY KEY($KEY_DAILY_DATE, $KEY_DAILY_FENCE_ID),
                FOREIGN KEY($KEY_DAILY_FENCE_ID) REFERENCES $TABLE_FENCES($KEY_ID) ON DELETE CASCADE
            )
        """.trimIndent())

        db.execSQL("""
            CREATE TABLE $TABLE_EXPORT_META (
                $KEY_EXPORT_ID TEXT PRIMARY KEY,
                $KEY_EXPORT_TYPE TEXT NOT NULL,
                $KEY_EXPORT_FILENAME TEXT NOT NULL,
                $KEY_EXPORT_RECORD_COUNT INTEGER NOT NULL DEFAULT 0,
                $KEY_EXPORT_TIMESTAMP INTEGER NOT NULL
            )
        """.trimIndent())

        db.execSQL("CREATE INDEX idx_fences_created ON $TABLE_FENCES($KEY_CREATED_AT)")
        db.execSQL("CREATE INDEX idx_fences_category ON $TABLE_FENCES($KEY_CATEGORY_ID)")
        db.execSQL("CREATE INDEX idx_categories_sort ON $TABLE_CATEGORIES($KEY_CATEGORY_SORT_ORDER)")
        db.execSQL("CREATE INDEX idx_events_fence ON $TABLE_EVENTS($KEY_FENCE_ID)")
        db.execSQL("CREATE INDEX idx_events_timestamp ON $TABLE_EVENTS($KEY_TIMESTAMP)")
        db.execSQL("CREATE INDEX idx_stays_fence ON $TABLE_STAYS($KEY_FENCE_ID)")
        db.execSQL("CREATE INDEX idx_daily_stays_date ON $TABLE_DAILY_STAYS($KEY_DAILY_DATE)")
        db.execSQL("CREATE INDEX idx_export_timestamp ON $TABLE_EXPORT_META($KEY_EXPORT_TIMESTAMP)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            try {
                db.execSQL("ALTER TABLE $TABLE_FENCES ADD COLUMN $KEY_CATEGORY_ID TEXT DEFAULT 'unassigned'")
            } catch (e: Exception) {
                AppLog.e("GeoFenceDB", "添加 category_id 列失败", e)
            }
            try {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS $TABLE_CATEGORIES (
                        $KEY_CATEGORY_TABLE_ID TEXT PRIMARY KEY,
                        $KEY_CATEGORY_NAME TEXT NOT NULL UNIQUE,
                        $KEY_CATEGORY_ICON TEXT,
                        $KEY_CATEGORY_SORT_ORDER INTEGER DEFAULT 0,
                        $KEY_CATEGORY_CREATED_AT INTEGER NOT NULL
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_fences_category ON $TABLE_FENCES($KEY_CATEGORY_ID)")
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_categories_sort ON $TABLE_CATEGORIES($KEY_CATEGORY_SORT_ORDER)")
            } catch (e: Exception) {
                AppLog.e("GeoFenceDB", "创建分类表失败", e)
            }
        }

        if (oldVersion < 3) {
            try {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS $TABLE_DAILY_STAYS (
                        $KEY_DAILY_DATE TEXT NOT NULL,
                        $KEY_DAILY_FENCE_ID TEXT NOT NULL,
                        $KEY_DAILY_FENCE_NAME TEXT NOT NULL,
                        $KEY_DAILY_DURATION INTEGER NOT NULL DEFAULT 0,
                        $KEY_DAILY_LAST_UPDATED INTEGER NOT NULL,
                        PRIMARY KEY($KEY_DAILY_DATE, $KEY_DAILY_FENCE_ID),
                        FOREIGN KEY($KEY_DAILY_FENCE_ID) REFERENCES $TABLE_FENCES($KEY_ID) ON DELETE CASCADE
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_daily_stays_date ON $TABLE_DAILY_STAYS($KEY_DAILY_DATE)")
            } catch (e: Exception) {
                AppLog.e("GeoFenceDB", "创建按日停留记录表失败", e)
            }
        }

        if (oldVersion < 4) {
            try {
                db.execSQL("ALTER TABLE $TABLE_FENCES ADD COLUMN $KEY_COLOR TEXT DEFAULT '#FF0000'")
            } catch (e: Exception) {
                AppLog.e("GeoFenceDB", "添加围栏颜色列失败", e)
            }
        }

        if (oldVersion < 5) {
            try {
                val cursor = db.rawQuery("PRAGMA table_info($TABLE_FENCES)", null)
                var columnExists = false
                if (cursor != null) {
                    while (cursor.moveToNext()) {
                        if (cursor.getString(cursor.getColumnIndexOrThrow("name")) == KEY_ALPHA) {
                            columnExists = true
                            break
                        }
                    }
                    cursor.close()
                }
                if (!columnExists) {
                    db.execSQL("ALTER TABLE $TABLE_FENCES ADD COLUMN $KEY_ALPHA REAL DEFAULT 0.3")
                }
            } catch (e: Exception) {
                AppLog.e("GeoFenceDB", "添加围栏透明度列失败", e)
            }
        }

        if (oldVersion < 6) {
            try {
                val cursor = db.rawQuery("PRAGMA table_info($TABLE_FENCES)", null)
                var columnExists = false
                if (cursor != null) {
                    while (cursor.moveToNext()) {
                        if (cursor.getString(cursor.getColumnIndexOrThrow("name")) == KEY_ALPHA) {
                            columnExists = true
                            break
                        }
                    }
                    cursor.close()
                }
                if (!columnExists) {
                    db.execSQL("ALTER TABLE $TABLE_FENCES ADD COLUMN $KEY_ALPHA REAL DEFAULT 0.3")
                }
            } catch (e: Exception) {
                AppLog.e("GeoFenceDB", "v6迁移：添加围栏透明度列失败", e)
            }
        }

        if (oldVersion < 7) {
            try {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS $TABLE_EXPORT_META (
                        $KEY_EXPORT_ID TEXT PRIMARY KEY,
                        $KEY_EXPORT_TYPE TEXT NOT NULL,
                        $KEY_EXPORT_FILENAME TEXT NOT NULL,
                        $KEY_EXPORT_RECORD_COUNT INTEGER NOT NULL DEFAULT 0,
                        $KEY_EXPORT_TIMESTAMP INTEGER NOT NULL
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_export_timestamp ON $TABLE_EXPORT_META($KEY_EXPORT_TIMESTAMP)")
            } catch (e: Exception) {
                AppLog.e("GeoFenceDB", "v7迁移：创建导出元数据表失败", e)
            }
        }
    }
}
