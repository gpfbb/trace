package com.trackmaster.app

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log
import com.trackmaster.app.data.GeoFence
import com.trackmaster.app.data.GeoFencePoint
import com.trackmaster.app.core.GeoFenceType
import com.trackmaster.app.data.GeoFenceAction
import com.trackmaster.app.data.DailyFenceStats
import com.trackmaster.app.data.FenceStayRecord
import com.trackmaster.app.data.GeoFenceEvent
import org.json.JSONArray
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentHashMap

class GeoFenceDatabaseHelper(context: Context) : SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    companion object {
        private const val DATABASE_NAME = "geofence.db"
        private const val DATABASE_VERSION = 7

        // 围栏表
        private const val TABLE_FENCES = "fences"
        private const val KEY_ID = "id"
        private const val KEY_NAME = "name"
        private const val KEY_TYPE = "type"
        private const val KEY_CENTER_LAT = "center_lat"
        private const val KEY_CENTER_LNG = "center_lng"
        private const val KEY_RADIUS = "radius"
        private const val KEY_POINTS = "points"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_CREATED_AT = "created_at"
        private const val KEY_CATEGORY_ID = "category_id"
        private const val KEY_COLOR = "color"
        private const val KEY_ALPHA = "alpha"

        // 分类表
        private const val TABLE_CATEGORIES = "categories"
        private const val KEY_CATEGORY_TABLE_ID = "id"
        private const val KEY_CATEGORY_NAME = "name"
        private const val KEY_CATEGORY_ICON = "icon"
        private const val KEY_CATEGORY_SORT_ORDER = "sort_order"
        private const val KEY_CATEGORY_CREATED_AT = "created_at"

        // 事件表
        private const val TABLE_EVENTS = "events"
        private const val KEY_EVENT_ID = "event_id"
        private const val KEY_FENCE_ID = "fence_id"
        private const val KEY_FENCE_NAME = "fence_name"
        private const val KEY_ACTION = "action"
        private const val KEY_TIMESTAMP = "timestamp"
        private const val KEY_LATITUDE = "latitude"
        private const val KEY_LONGITUDE = "longitude"
        private const val KEY_ACCURACY = "accuracy"

        // 停留记录表
        private const val TABLE_STAYS = "stays"
        private const val KEY_STAY_ID = "stay_id"
        private const val KEY_ENTER_TIME = "enter_time"
        private const val KEY_EXIT_TIME = "exit_time"
        private const val KEY_DURATION = "duration"

        // 按日停留记录表
        private const val TABLE_DAILY_STAYS = "daily_stays"
        private const val KEY_DAILY_DATE = "daily_date"
        private const val KEY_DAILY_FENCE_ID = "daily_fence_id"
        private const val KEY_DAILY_FENCE_NAME = "daily_fence_name"
        private const val KEY_DAILY_DURATION = "daily_duration"
        private const val KEY_DAILY_LAST_UPDATED = "daily_last_updated"

        // 导出元数据表
        private const val TABLE_EXPORT_META = "export_meta"
        private const val KEY_EXPORT_ID = "export_id"
        private const val KEY_EXPORT_TYPE = "export_type"
        private const val KEY_EXPORT_FILENAME = "filename"
        private const val KEY_EXPORT_RECORD_COUNT = "record_count"
        private const val KEY_EXPORT_TIMESTAMP = "export_timestamp"
    }

    // 内存缓存：使用 ConcurrentHashMap 保证线程安全
    private val fenceCache = ConcurrentHashMap<String, GeoFence>()
    private val allFencesCache = mutableListOf<GeoFence>()
    private var allFencesCacheValid = false

    private val categoryCache = ConcurrentHashMap<String, Category>()
    private val allCategoriesCache = mutableListOf<Category>()
    private var allCategoriesCacheValid = false

    /** 使围栏缓存失效 */
    private fun invalidateFenceCache() {
        fenceCache.clear()
        allFencesCacheValid = false
        Log.d("GeoFenceDB", "围栏缓存已失效")
    }

    /** 使分类缓存失效 */
    private fun invalidateCategoryCache() {
        categoryCache.clear()
        allCategoriesCacheValid = false
        Log.d("GeoFenceDB", "分类缓存已失效")
    }

    override fun onOpen(db: SQLiteDatabase) {
        super.onOpen(db)
        db.execSQL("PRAGMA foreign_keys = ON")
    }

    override fun onCreate(db: SQLiteDatabase) {
        val createFencesTable = """
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
        """.trimIndent()

        val createCategoriesTable = """
            CREATE TABLE $TABLE_CATEGORIES (
                $KEY_CATEGORY_TABLE_ID TEXT PRIMARY KEY,
                $KEY_CATEGORY_NAME TEXT NOT NULL UNIQUE,
                $KEY_CATEGORY_ICON TEXT,
                $KEY_CATEGORY_SORT_ORDER INTEGER DEFAULT 0,
                $KEY_CATEGORY_CREATED_AT INTEGER NOT NULL
            )
        """.trimIndent()

        val createEventsTable = """
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
        """.trimIndent()

        val createStaysTable = """
            CREATE TABLE $TABLE_STAYS (
                $KEY_STAY_ID TEXT PRIMARY KEY,
                $KEY_FENCE_ID TEXT NOT NULL,
                $KEY_FENCE_NAME TEXT NOT NULL,
                $KEY_ENTER_TIME INTEGER NOT NULL,
                $KEY_EXIT_TIME INTEGER,
                $KEY_DURATION INTEGER,
                FOREIGN KEY($KEY_FENCE_ID) REFERENCES $TABLE_FENCES($KEY_ID) ON DELETE CASCADE
            )
        """.trimIndent()

        val createDailyStaysTable = """
            CREATE TABLE $TABLE_DAILY_STAYS (
                $KEY_DAILY_DATE TEXT NOT NULL,
                $KEY_DAILY_FENCE_ID TEXT NOT NULL,
                $KEY_DAILY_FENCE_NAME TEXT NOT NULL,
                $KEY_DAILY_DURATION INTEGER NOT NULL DEFAULT 0,
                $KEY_DAILY_LAST_UPDATED INTEGER NOT NULL,
                PRIMARY KEY($KEY_DAILY_DATE, $KEY_DAILY_FENCE_ID),
                FOREIGN KEY($KEY_DAILY_FENCE_ID) REFERENCES $TABLE_FENCES($KEY_ID) ON DELETE CASCADE
            )
        """.trimIndent()

        val createExportMetaTable = """
            CREATE TABLE $TABLE_EXPORT_META (
                $KEY_EXPORT_ID TEXT PRIMARY KEY,
                $KEY_EXPORT_TYPE TEXT NOT NULL,
                $KEY_EXPORT_FILENAME TEXT NOT NULL,
                $KEY_EXPORT_RECORD_COUNT INTEGER NOT NULL DEFAULT 0,
                $KEY_EXPORT_TIMESTAMP INTEGER NOT NULL
            )
        """.trimIndent()

        db.execSQL(createFencesTable)
        db.execSQL(createCategoriesTable)
        db.execSQL(createEventsTable)
        db.execSQL(createStaysTable)
        db.execSQL(createDailyStaysTable)
        db.execSQL(createExportMetaTable)

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
                Log.e("GeoFenceDB", "添加 category_id 列失败", e)
            }

            val createCategoriesTable = """
                CREATE TABLE IF NOT EXISTS $TABLE_CATEGORIES (
                    $KEY_CATEGORY_TABLE_ID TEXT PRIMARY KEY,
                    $KEY_CATEGORY_NAME TEXT NOT NULL UNIQUE,
                    $KEY_CATEGORY_ICON TEXT,
                    $KEY_CATEGORY_SORT_ORDER INTEGER DEFAULT 0,
                    $KEY_CATEGORY_CREATED_AT INTEGER NOT NULL
                )
            """.trimIndent()

            try {
                db.execSQL(createCategoriesTable)
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_fences_category ON $TABLE_FENCES($KEY_CATEGORY_ID)")
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_categories_sort ON $TABLE_CATEGORIES($KEY_CATEGORY_SORT_ORDER)")
            } catch (e: Exception) {
                Log.e("GeoFenceDB", "创建分类表失败", e)
            }
        }

        if (oldVersion < 3) {
            val createDailyStaysTable = """
                CREATE TABLE IF NOT EXISTS $TABLE_DAILY_STAYS (
                    $KEY_DAILY_DATE TEXT NOT NULL,
                    $KEY_DAILY_FENCE_ID TEXT NOT NULL,
                    $KEY_DAILY_FENCE_NAME TEXT NOT NULL,
                    $KEY_DAILY_DURATION INTEGER NOT NULL DEFAULT 0,
                    $KEY_DAILY_LAST_UPDATED INTEGER NOT NULL,
                    PRIMARY KEY($KEY_DAILY_DATE, $KEY_DAILY_FENCE_ID),
                    FOREIGN KEY($KEY_DAILY_FENCE_ID) REFERENCES $TABLE_FENCES($KEY_ID) ON DELETE CASCADE
                )
            """.trimIndent()

            try {
                db.execSQL(createDailyStaysTable)
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_daily_stays_date ON $TABLE_DAILY_STAYS($KEY_DAILY_DATE)")
                Log.d("GeoFenceDB", "创建按日停留记录表成功")
            } catch (e: Exception) {
                Log.e("GeoFenceDB", "创建按日停留记录表失败", e)
            }
        }

        if (oldVersion < 4) {
            try {
                db.execSQL("ALTER TABLE $TABLE_FENCES ADD COLUMN $KEY_COLOR TEXT DEFAULT '#FF0000'")
                Log.d("GeoFenceDB", "添加围栏颜色列成功")
            } catch (e: Exception) {
                Log.e("GeoFenceDB", "添加围栏颜色列失败", e)
            }
        }

        if (oldVersion < 5) {
            try {
                // 先检查列是否已存在
                val cursor = db.rawQuery("PRAGMA table_info($TABLE_FENCES)", null)
                var columnExists = false
                if (cursor != null) {
                    while (cursor.moveToNext()) {
                        val columnName = cursor.getString(cursor.getColumnIndexOrThrow("name"))
                        if (columnName == KEY_ALPHA) {
                            columnExists = true
                            break
                        }
                    }
                    cursor.close()
                }
                if (!columnExists) {
                    db.execSQL("ALTER TABLE $TABLE_FENCES ADD COLUMN $KEY_ALPHA REAL DEFAULT 0.3")
                    Log.d("GeoFenceDB", "添加围栏透明度列成功")
                } else {
                    Log.d("GeoFenceDB", "透明度列已存在，跳过添加")
                }
            } catch (e: Exception) {
                Log.e("GeoFenceDB", "添加围栏透明度列失败", e)
            }
        }

        if (oldVersion < 6) {
            // 确保 alpha 列存在（处理从 v5 全新安装但 onCreate 没有该列的情况）
            try {
                val cursor = db.rawQuery("PRAGMA table_info($TABLE_FENCES)", null)
                var columnExists = false
                if (cursor != null) {
                    while (cursor.moveToNext()) {
                        val columnName = cursor.getString(cursor.getColumnIndexOrThrow("name"))
                        if (columnName == KEY_ALPHA) {
                            columnExists = true
                            break
                        }
                    }
                    cursor.close()
                }
                if (!columnExists) {
                    db.execSQL("ALTER TABLE $TABLE_FENCES ADD COLUMN $KEY_ALPHA REAL DEFAULT 0.3")
                    Log.d("GeoFenceDB", "v6迁移：添加围栏透明度列成功")
                }
            } catch (e: Exception) {
                Log.e("GeoFenceDB", "v6迁移：添加围栏透明度列失败", e)
            }
        }

        if (oldVersion < 7) {
            try {
                val createExportMetaTable = """
                    CREATE TABLE IF NOT EXISTS $TABLE_EXPORT_META (
                        $KEY_EXPORT_ID TEXT PRIMARY KEY,
                        $KEY_EXPORT_TYPE TEXT NOT NULL,
                        $KEY_EXPORT_FILENAME TEXT NOT NULL,
                        $KEY_EXPORT_RECORD_COUNT INTEGER NOT NULL DEFAULT 0,
                        $KEY_EXPORT_TIMESTAMP INTEGER NOT NULL
                    )
                """.trimIndent()
                db.execSQL(createExportMetaTable)
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_export_timestamp ON $TABLE_EXPORT_META($KEY_EXPORT_TIMESTAMP)")
                Log.d("GeoFenceDB", "v7迁移：创建导出元数据表成功")
            } catch (e: Exception) {
                Log.e("GeoFenceDB", "v7迁移：创建导出元数据表失败", e)
            }
        }
    }

    fun insertFence(fence: GeoFence): Long {
        val db = writableDatabase
        val values = ContentValues()
        values.put(KEY_ID, fence.id)
        values.put(KEY_NAME, fence.name)
        values.put(KEY_TYPE, fence.type.name)
        values.put(KEY_CENTER_LAT, fence.center?.latitude)
        values.put(KEY_CENTER_LNG, fence.center?.longitude)
        values.put(KEY_RADIUS, fence.radius)
        values.put(KEY_POINTS, fence.points?.let { pointsToJson(it) })
        values.put(KEY_ENABLED, 1)
        values.put(KEY_CREATED_AT, fence.createdAt.time)
        values.put(KEY_CATEGORY_ID, fence.categoryId)
        values.put(KEY_COLOR, fence.color)
        values.put(KEY_ALPHA, fence.alpha)

        val result = db.insert(TABLE_FENCES, null, values)
        invalidateFenceCache()
        return result
    }

    fun getAllFences(): List<GeoFence> {
        if (allFencesCacheValid) {
            Log.d("GeoFenceDB", "getAllFences 返回缓存: ${allFencesCache.size} 个")
            return allFencesCache.toList()
        }

        Log.d("GeoFenceDB", "getAllFences 从数据库查询")
        val fences = mutableListOf<GeoFence>()
        val db = readableDatabase
        val cursor = db.query(TABLE_FENCES, null, null, null, null, null, "$KEY_CREATED_AT DESC")

        try {
            Log.d("GeoFenceDB", "Cursor行数: ${cursor.count}")
            if (cursor.moveToFirst()) {
                do {
                    try {
                        val id = cursor.getString(cursor.getColumnIndexOrThrow(KEY_ID))
                        val name = cursor.getString(cursor.getColumnIndexOrThrow(KEY_NAME))
                        val type = GeoFenceType.valueOf(cursor.getString(cursor.getColumnIndexOrThrow(KEY_TYPE)))
                        val pointsJson = cursor.getString(cursor.getColumnIndexOrThrow(KEY_POINTS))
                        val createdAt = Date(cursor.getLong(cursor.getColumnIndexOrThrow(KEY_CREATED_AT)))
                        val categoryId = if (cursor.getColumnIndex(KEY_CATEGORY_ID) >= 0) {
                            cursor.getString(cursor.getColumnIndexOrThrow(KEY_CATEGORY_ID))
                        } else {
                            Category.UNASSIGNED_ID
                        }

                        val color = if (cursor.getColumnIndex(KEY_COLOR) >= 0 && !cursor.isNull(cursor.getColumnIndex(KEY_COLOR))) {
                            cursor.getString(cursor.getColumnIndex(KEY_COLOR))
                        } else {
                            "#FF0000"
                        }

                        val alpha = if (cursor.getColumnIndex(KEY_ALPHA) >= 0 && !cursor.isNull(cursor.getColumnIndex(KEY_ALPHA))) {
                            cursor.getFloat(cursor.getColumnIndex(KEY_ALPHA))
                        } else {
                            0.3f
                        }

                        // 安全读取中心点和半径（多边形围栏这些是NULL）
                        val center = if (cursor.getColumnIndex(KEY_CENTER_LAT) >= 0 && !cursor.isNull(cursor.getColumnIndex(KEY_CENTER_LAT))) {
                            GeoFencePoint(
                                cursor.getDouble(cursor.getColumnIndex(KEY_CENTER_LAT)),
                                cursor.getDouble(cursor.getColumnIndex(KEY_CENTER_LNG))
                            )
                        } else null

                        val radius = if (cursor.getColumnIndex(KEY_RADIUS) >= 0 && !cursor.isNull(cursor.getColumnIndex(KEY_RADIUS))) {
                            cursor.getFloat(cursor.getColumnIndex(KEY_RADIUS))
                        } else null

                        val points = if (!pointsJson.isNullOrEmpty()) jsonToPoints(pointsJson) else null

                        val fence = GeoFence(
                            id = id,
                            name = name,
                            type = type,
                            center = center,
                            radius = radius,
                            points = points,
                            categoryId = categoryId,
                            createdAt = createdAt,
                            color = color,
                            alpha = alpha
                        )
                        fences.add(fence)
                        fenceCache[id] = fence
                        Log.d("GeoFenceDB", "读取围栏: id=$id, name=$name, type=$type, center=$center, radius=$radius, pointsCount=${points?.size}")
                    } catch (e: Exception) {
                        Log.e("GeoFenceDB", "读取单个围栏出错: ${e.message}")
                    }
                } while (cursor.moveToNext())
            }
        } finally {
            cursor.close()
        }

        allFencesCache.clear()
        allFencesCache.addAll(fences)
        allFencesCacheValid = true
        Log.d("GeoFenceDB", "getAllFences 返回 ${fences.size} 个围栏")
        return fences
    }

    /** 根据 ID 查询单个围栏 */
    fun getFenceById(fenceId: String): GeoFence? {
        // 先查缓存
        fenceCache[fenceId]?.let { return it }

        val db = readableDatabase
        val cursor = db.query(
            TABLE_FENCES, null,
            "$KEY_ID = ?", arrayOf(fenceId),
            null, null, null
        )

        try {
            if (cursor.moveToFirst()) {
                val id = cursor.getString(cursor.getColumnIndexOrThrow(KEY_ID))
                val name = cursor.getString(cursor.getColumnIndexOrThrow(KEY_NAME))
                val type = GeoFenceType.valueOf(cursor.getString(cursor.getColumnIndexOrThrow(KEY_TYPE)))
                val pointsJson = cursor.getString(cursor.getColumnIndexOrThrow(KEY_POINTS))
                val createdAt = Date(cursor.getLong(cursor.getColumnIndexOrThrow(KEY_CREATED_AT)))
                val categoryId = if (cursor.getColumnIndex(KEY_CATEGORY_ID) >= 0) {
                    cursor.getString(cursor.getColumnIndexOrThrow(KEY_CATEGORY_ID))
                } else {
                    Category.UNASSIGNED_ID
                }
                val color = if (cursor.getColumnIndex(KEY_COLOR) >= 0 && !cursor.isNull(cursor.getColumnIndex(KEY_COLOR))) {
                    cursor.getString(cursor.getColumnIndex(KEY_COLOR))
                } else "#FF0000"
                val alpha = if (cursor.getColumnIndex(KEY_ALPHA) >= 0 && !cursor.isNull(cursor.getColumnIndex(KEY_ALPHA))) {
                    cursor.getFloat(cursor.getColumnIndex(KEY_ALPHA))
                } else 0.3f
                val center = if (cursor.getColumnIndex(KEY_CENTER_LAT) >= 0 && !cursor.isNull(cursor.getColumnIndex(KEY_CENTER_LAT))) {
                    GeoFencePoint(
                        cursor.getDouble(cursor.getColumnIndex(KEY_CENTER_LAT)),
                        cursor.getDouble(cursor.getColumnIndex(KEY_CENTER_LNG))
                    )
                } else null
                val radius = if (cursor.getColumnIndex(KEY_RADIUS) >= 0 && !cursor.isNull(cursor.getColumnIndex(KEY_RADIUS))) {
                    cursor.getFloat(cursor.getColumnIndex(KEY_RADIUS))
                } else null
                val points = if (!pointsJson.isNullOrEmpty()) jsonToPoints(pointsJson) else null

                return GeoFence(
                    id = id, name = name, type = type,
                    center = center, radius = radius, points = points,
                    categoryId = categoryId, createdAt = createdAt,
                    color = color, alpha = alpha
                ).also { fenceCache[fenceId] = it }
            }
        } finally {
            cursor.close()
        }
        return null
    }

    fun insertCategory(category: Category): Long {
        val db = writableDatabase
        val values = ContentValues()
        values.put(KEY_CATEGORY_TABLE_ID, category.id)
        values.put(KEY_CATEGORY_NAME, category.name)
        values.put(KEY_CATEGORY_ICON, category.icon)
        values.put(KEY_CATEGORY_SORT_ORDER, category.sortOrder)
        values.put(KEY_CATEGORY_CREATED_AT, category.createdAt.time)

        val result = db.insert(TABLE_CATEGORIES, null, values)
        invalidateCategoryCache()
        return result
    }

    fun getAllCategories(): List<Category> {
        if (allCategoriesCacheValid) {
            return allCategoriesCache.toList()
        }

        val categories = mutableListOf<Category>()
        val db = readableDatabase
        val cursor = db.query(
            TABLE_CATEGORIES,
            null,
            null,
            null,
            null,
            null,
            "$KEY_CATEGORY_SORT_ORDER ASC, $KEY_CATEGORY_CREATED_AT ASC"
        )

        try {
            if (cursor.moveToFirst()) {
                do {
                    val id = cursor.getString(cursor.getColumnIndexOrThrow(KEY_CATEGORY_TABLE_ID))
                    val name = cursor.getString(cursor.getColumnIndexOrThrow(KEY_CATEGORY_NAME))
                    val icon = if (cursor.getColumnIndex(KEY_CATEGORY_ICON) >= 0 && !cursor.isNull(cursor.getColumnIndexOrThrow(KEY_CATEGORY_ICON))) {
                        cursor.getString(cursor.getColumnIndexOrThrow(KEY_CATEGORY_ICON))
                    } else null
                    val sortOrder = if (cursor.getColumnIndex(KEY_CATEGORY_SORT_ORDER) >= 0) {
                        cursor.getInt(cursor.getColumnIndexOrThrow(KEY_CATEGORY_SORT_ORDER))
                    } else 0
                    val createdAt = Date(cursor.getLong(cursor.getColumnIndexOrThrow(KEY_CATEGORY_CREATED_AT)))

                    val category = Category(
                        id = id,
                        name = name,
                        icon = icon,
                        sortOrder = sortOrder,
                        createdAt = createdAt
                    )
                    categories.add(category)
                    categoryCache[id] = category
                } while (cursor.moveToNext())
            }
        } finally {
            cursor.close()
        }

        allCategoriesCache.clear()
        allCategoriesCache.addAll(categories)
        allCategoriesCacheValid = true
        return categories
    }

    fun updateCategory(category: Category): Int {
        val db = writableDatabase
        val values = ContentValues()
        values.put(KEY_CATEGORY_NAME, category.name)
        values.put(KEY_CATEGORY_ICON, category.icon)
        values.put(KEY_CATEGORY_SORT_ORDER, category.sortOrder)

        val result = db.update(
            TABLE_CATEGORIES,
            values,
            "$KEY_CATEGORY_TABLE_ID = ?",
            arrayOf(category.id)
        )
        invalidateCategoryCache()
        return result
    }

    fun deleteCategory(categoryId: String): Int {
        val db = writableDatabase
        val result = db.delete(
            TABLE_CATEGORIES,
            "$KEY_CATEGORY_TABLE_ID = ?",
            arrayOf(categoryId)
        )
        invalidateCategoryCache()
        invalidateFenceCache()
        return result
    }

    fun moveFencesToCategory(fromCategoryId: String, toCategoryId: String): Int {
        val db = writableDatabase
        val values = ContentValues()
        values.put(KEY_CATEGORY_ID, toCategoryId)

        val result = db.update(
            TABLE_FENCES,
            values,
            "$KEY_CATEGORY_ID = ?",
            arrayOf(fromCategoryId)
        )
        invalidateFenceCache()
        return result
    }

    fun updateFenceCategory(fenceId: String, categoryId: String): Int {
        val db = writableDatabase
        val values = ContentValues()
        values.put(KEY_CATEGORY_ID, categoryId)

        val result = db.update(
            TABLE_FENCES,
            values,
            "$KEY_ID = ?",
            arrayOf(fenceId)
        )
        invalidateFenceCache()
        return result
    }

    /**
     * 更新围栏信息（名称、中心点、半径、多边形点）
     * @param fence 更新后的围栏对象，ID必须保持不变
     * @return 更新的行数
     */
    fun updateFence(fence: GeoFence): Int {
        val db = writableDatabase
        val values = ContentValues()
        values.put(KEY_NAME, fence.name)
        values.put(KEY_TYPE, fence.type.name)
        values.put(KEY_CENTER_LAT, fence.center?.latitude)
        values.put(KEY_CENTER_LNG, fence.center?.longitude)
        values.put(KEY_RADIUS, fence.radius)
        values.put(KEY_POINTS, fence.points?.let { pointsToJson(it) })
        values.put(KEY_CATEGORY_ID, fence.categoryId)
        values.put(KEY_COLOR, fence.color)
        values.put(KEY_ALPHA, fence.alpha)
        // 注意：不更新CREATED_AT字段，保持原始创建时间

        val result = db.update(
            TABLE_FENCES,
            values,
            "$KEY_ID = ?",
            arrayOf(fence.id)
        )
        invalidateFenceCache()
        return result
    }

    fun getCategoryByName(name: String): Category? {
        categoryCache.values.find { it.name == name }?.let { return it }

        val db = readableDatabase
        val cursor = db.query(
            TABLE_CATEGORIES,
            null,
            "$KEY_CATEGORY_NAME = ?",
            arrayOf(name),
            null,
            null,
            null
        )

        var category: Category? = null
        try {
            if (cursor.moveToFirst()) {
                val id = cursor.getString(cursor.getColumnIndexOrThrow(KEY_CATEGORY_TABLE_ID))
                val categoryName = cursor.getString(cursor.getColumnIndexOrThrow(KEY_CATEGORY_NAME))
                val icon = if (cursor.getColumnIndex(KEY_CATEGORY_ICON) >= 0 && !cursor.isNull(cursor.getColumnIndexOrThrow(KEY_CATEGORY_ICON))) {
                    cursor.getString(cursor.getColumnIndexOrThrow(KEY_CATEGORY_ICON))
                } else null
                val sortOrder = if (cursor.getColumnIndex(KEY_CATEGORY_SORT_ORDER) >= 0) {
                    cursor.getInt(cursor.getColumnIndexOrThrow(KEY_CATEGORY_SORT_ORDER))
                } else 0
                val createdAt = Date(cursor.getLong(cursor.getColumnIndexOrThrow(KEY_CATEGORY_CREATED_AT)))

                category = Category(
                    id = id,
                    name = categoryName,
                    icon = icon,
                    sortOrder = sortOrder,
                    createdAt = createdAt
                )
                categoryCache[category.id] = category
            }
        } finally {
            cursor.close()
        }
        return category
    }

    fun getFencesByCategory(categoryId: String): List<GeoFence> {
        val fences = mutableListOf<GeoFence>()
        val db = readableDatabase
        val cursor = db.query(
            TABLE_FENCES,
            null,
            "$KEY_CATEGORY_ID = ?",
            arrayOf(categoryId),
            null,
            null,
            "$KEY_CREATED_AT DESC"
        )

        try {
            if (cursor.moveToFirst()) {
                do {
                    val id = cursor.getString(cursor.getColumnIndexOrThrow(KEY_ID))
                    val name = cursor.getString(cursor.getColumnIndexOrThrow(KEY_NAME))
                    val type = GeoFenceType.valueOf(cursor.getString(cursor.getColumnIndexOrThrow(KEY_TYPE)))
                    val centerLat = cursor.getDouble(cursor.getColumnIndexOrThrow(KEY_CENTER_LAT))
                    val centerLng = cursor.getDouble(cursor.getColumnIndexOrThrow(KEY_CENTER_LNG))
                    val radius = cursor.getFloat(cursor.getColumnIndexOrThrow(KEY_RADIUS))
                    val pointsJson = cursor.getString(cursor.getColumnIndexOrThrow(KEY_POINTS))
                    val createdAt = Date(cursor.getLong(cursor.getColumnIndexOrThrow(KEY_CREATED_AT)))
                    val categoryIdVal = if (cursor.getColumnIndex(KEY_CATEGORY_ID) >= 0) {
                        cursor.getString(cursor.getColumnIndexOrThrow(KEY_CATEGORY_ID))
                    } else {
                        Category.UNASSIGNED_ID
                    }

                    val color = if (cursor.getColumnIndex(KEY_COLOR) >= 0 && !cursor.isNull(cursor.getColumnIndex(KEY_COLOR))) {
                        cursor.getString(cursor.getColumnIndex(KEY_COLOR))
                    } else {
                        "#FF0000"
                    }

                    val alpha = if (cursor.getColumnIndex(KEY_ALPHA) >= 0 && !cursor.isNull(cursor.getColumnIndex(KEY_ALPHA))) {
                        cursor.getFloat(cursor.getColumnIndex(KEY_ALPHA))
                    } else {
                        0.3f
                    }

                    val center = if (!cursor.isNull(cursor.getColumnIndexOrThrow(KEY_CENTER_LAT))) {
                        GeoFencePoint(centerLat, centerLng)
                    } else null

                    val points = if (!pointsJson.isNullOrEmpty()) jsonToPoints(pointsJson) else null

                    fences.add(
                        GeoFence(
                            id = id,
                            name = name,
                            type = type,
                            center = center,
                            radius = if (!cursor.isNull(cursor.getColumnIndexOrThrow(KEY_RADIUS))) radius else null,
                            points = points,
                            categoryId = categoryIdVal,
                            createdAt = createdAt,
                            color = color,
                            alpha = alpha
                        )
                    )
                } while (cursor.moveToNext())
            }
        } finally {
            cursor.close()
        }
        return fences
    }

    fun deleteFence(fenceId: String): Int {
        val db = writableDatabase
        val result = db.delete(TABLE_FENCES, "$KEY_ID = ?", arrayOf(fenceId))
        invalidateFenceCache()
        return result
    }

    fun deleteAllFences(): Int {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete(TABLE_DAILY_STAYS, null, null)
            db.delete(TABLE_STAYS, null, null)
            db.delete(TABLE_EVENTS, null, null)
            val result = db.delete(TABLE_FENCES, null, null)
            db.setTransactionSuccessful()
            db.endTransaction()
            invalidateFenceCache()
            return result
        } catch (e: Exception) {
            db.endTransaction()
            Log.e("GeoFenceDB", "删除所有围栏失败", e)
            invalidateFenceCache()
            return 0
        }
    }

    fun insertEvent(event: GeoFenceEvent): Long {
        val db = writableDatabase
        val values = ContentValues()
        values.put(KEY_EVENT_ID, event.id)
        values.put(KEY_FENCE_ID, event.fenceId)
        values.put(KEY_FENCE_NAME, event.fenceName)
        values.put(KEY_ACTION, event.action.name)
        values.put(KEY_TIMESTAMP, event.time.time)
        values.put(KEY_LATITUDE, event.latitude)
        values.put(KEY_LONGITUDE, event.longitude)
        values.put(KEY_ACCURACY, event.accuracy)

        val result = db.insert(TABLE_EVENTS, null, values)
        return result
    }

    fun insertEvents(events: List<GeoFenceEvent>): Int {
        val db = writableDatabase
        var count = 0
        db.beginTransaction()
        try {
            for (event in events) {
                val values = ContentValues()
                values.put(KEY_EVENT_ID, event.id)
                values.put(KEY_FENCE_ID, event.fenceId)
                values.put(KEY_FENCE_NAME, event.fenceName)
                values.put(KEY_ACTION, event.action.name)
                values.put(KEY_TIMESTAMP, event.time.time)
                values.put(KEY_LATITUDE, event.latitude)
                values.put(KEY_LONGITUDE, event.longitude)
                values.put(KEY_ACCURACY, event.accuracy)
                db.insert(TABLE_EVENTS, null, values)
                count++
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return count
    }

    fun getEventsByFence(fenceId: String, limit: Int = 100): List<GeoFenceEvent> {
        return getEvents("$KEY_FENCE_ID = ?", arrayOf(fenceId), limit)
    }

    fun getEventsByTimeRange(startTime: Date, endTime: Date): List<GeoFenceEvent> {
        return getEvents(
            "$KEY_TIMESTAMP >= ? AND $KEY_TIMESTAMP <= ?",
            arrayOf(startTime.time.toString(), endTime.time.toString()),
            1000
        )
    }

    fun getAllEvents(limit: Int = 10000): List<GeoFenceEvent> {
        return getEvents(null, emptyArray(), limit)
    }

    private fun getEvents(selection: String?, selectionArgs: Array<String>?, limit: Int): List<GeoFenceEvent> {
        val events = mutableListOf<GeoFenceEvent>()
        val db = readableDatabase
        val cursor = db.query(
            TABLE_EVENTS,
            null,
            selection,
            selectionArgs,
            null,
            null,
            "$KEY_TIMESTAMP DESC",
            limit.toString()
        )

        try {
            if (cursor.moveToFirst()) {
                do {
                    events.add(cursorToEvent(cursor))
                } while (cursor.moveToNext())
            }
        } finally {
            cursor.close()
        }
        return events
    }

    private fun cursorToEvent(cursor: android.database.Cursor): GeoFenceEvent {
        return GeoFenceEvent(
            id = cursor.getString(cursor.getColumnIndexOrThrow(KEY_EVENT_ID)),
            fenceId = cursor.getString(cursor.getColumnIndexOrThrow(KEY_FENCE_ID)),
            fenceName = cursor.getString(cursor.getColumnIndexOrThrow(KEY_FENCE_NAME)),
            action = GeoFenceAction.valueOf(cursor.getString(cursor.getColumnIndexOrThrow(KEY_ACTION))),
            time = Date(cursor.getLong(cursor.getColumnIndexOrThrow(KEY_TIMESTAMP))),
            latitude = cursor.getDouble(cursor.getColumnIndexOrThrow(KEY_LATITUDE)),
            longitude = cursor.getDouble(cursor.getColumnIndexOrThrow(KEY_LONGITUDE)),
            accuracy = cursor.getFloat(cursor.getColumnIndexOrThrow(KEY_ACCURACY)),
            durationSeconds = null
        )
    }

    fun insertStayRecord(record: FenceStayRecord): Long {
        val db = writableDatabase
        val values = ContentValues()
        values.put(KEY_STAY_ID, UUID.randomUUID().toString())
        values.put(KEY_FENCE_ID, record.fenceId)
        values.put(KEY_FENCE_NAME, record.fenceName)
        values.put(KEY_ENTER_TIME, record.enterTime.time)
        values.put(KEY_EXIT_TIME, record.exitTime?.time)
        values.put(KEY_DURATION, record.durationSeconds)

        val result = db.insert(TABLE_STAYS, null, values)
        return result
    }

    /**
     * 更新开放停留记录的累积时长
     * 用于在应用关闭前保存当前累积的时长，以便在应用重启后恢复正确的计时状态
     */
    fun updateOpenStayAccumulatedDuration(fenceId: String, accumulatedDuration: Long): Int {
        val db = writableDatabase
        val values = ContentValues()
        values.put(KEY_DURATION, accumulatedDuration)

        val result = db.update(
            TABLE_STAYS,
            values,
            "$KEY_FENCE_ID = ? AND $KEY_EXIT_TIME IS NULL",
            arrayOf(fenceId)
        )
        Log.d("GeoFenceDB", "更新开放停留累积时长: fenceId=$fenceId, duration=$accumulatedDuration, updatedRows=$result")
        return result
    }

    /**
     * 关闭开放停留记录（设置退出时间和时长）
     * 用于 onFenceExit 时关闭对应的开放记录，而不是插入新记录
     */
    fun closeOpenStayRecord(fenceId: String, exitTime: Date, durationSeconds: Long): Int {
        val db = writableDatabase
        val values = ContentValues()
        values.put(KEY_EXIT_TIME, exitTime.time)
        values.put(KEY_DURATION, durationSeconds)

        val result = db.update(
            TABLE_STAYS,
            values,
            "$KEY_FENCE_ID = ? AND $KEY_EXIT_TIME IS NULL",
            arrayOf(fenceId)
        )
        Log.d("GeoFenceDB", "关闭开放停留记录: fenceId=$fenceId, exitTime=$exitTime, duration=$durationSeconds, updatedRows=$result")
        return result
    }

    fun getStayRecordsByFence(fenceId: String): List<FenceStayRecord> {
        val records = mutableListOf<FenceStayRecord>()
        val db = readableDatabase
        val cursor = db.query(
            TABLE_STAYS,
            null,
            "$KEY_FENCE_ID = ?",
            arrayOf(fenceId),
            null,
            null,
            "$KEY_ENTER_TIME DESC"
        )

        try {
            if (cursor.moveToFirst()) {
                do {
                    records.add(cursorToStayRecord(cursor))
                } while (cursor.moveToNext())
            }
        } finally {
            cursor.close()
        }
        return records
    }

    fun getAllStayRecords(): List<FenceStayRecord> {
        val records = mutableListOf<FenceStayRecord>()
        val db = readableDatabase
        val cursor = db.query(TABLE_STAYS, null, null, null, null, null, "$KEY_ENTER_TIME DESC")

        try {
            if (cursor.moveToFirst()) {
                do {
                    records.add(cursorToStayRecord(cursor))
                } while (cursor.moveToNext())
            }
        } finally {
            cursor.close()
        }
        return records
    }

    private fun cursorToStayRecord(cursor: android.database.Cursor): FenceStayRecord {
        return FenceStayRecord(
            fenceId = cursor.getString(cursor.getColumnIndexOrThrow(KEY_FENCE_ID)),
            fenceName = cursor.getString(cursor.getColumnIndexOrThrow(KEY_FENCE_NAME)),
            enterTime = Date(cursor.getLong(cursor.getColumnIndexOrThrow(KEY_ENTER_TIME))),
            exitTime = if (!cursor.isNull(cursor.getColumnIndexOrThrow(KEY_EXIT_TIME))) {
                Date(cursor.getLong(cursor.getColumnIndexOrThrow(KEY_EXIT_TIME)))
            } else null,
            durationSeconds = if (!cursor.isNull(cursor.getColumnIndexOrThrow(KEY_DURATION))) {
                cursor.getLong(cursor.getColumnIndexOrThrow(KEY_DURATION))
            } else null
        )
    }

    fun getFenceStats(fenceId: String): Long {
        var totalDuration = 0L
        val db = readableDatabase
        val cursor = db.query(
            TABLE_STAYS,
            arrayOf("SUM($KEY_DURATION) as total"),
            "$KEY_FENCE_ID = ? AND $KEY_DURATION IS NOT NULL",
            arrayOf(fenceId),
            null,
            null,
            null
        )

        try {
            if (cursor.moveToFirst()) {
                totalDuration = cursor.getLong(0)
            }
        } finally {
            cursor.close()
        }
        return totalDuration
    }

    fun getDailyStats(date: String): Map<String, Long> {
        val stats = mutableMapOf<String, Long>()
        val db = readableDatabase
        val cursor = db.query(
            TABLE_STAYS,
            arrayOf(KEY_FENCE_ID, "SUM($KEY_DURATION) as total"),
            "DATE($KEY_ENTER_TIME/1000, 'unixepoch') = ?",
            arrayOf(date),
            KEY_FENCE_ID,
            null,
            null
        )

        try {
            if (cursor.moveToFirst()) {
                do {
                    val fenceId = cursor.getString(0)
                    val total = cursor.getLong(1)
                    stats[fenceId] = total
                } while (cursor.moveToNext())
            }
        } finally {
            cursor.close()
        }
        return stats
    }

    fun getDailyStatsByFenceAndDate(fenceId: String, date: String): DailyFenceStats? {
        val db = readableDatabase
        val cursor = db.query(
            TABLE_STAYS,
            arrayOf(
                KEY_FENCE_ID,
                KEY_FENCE_NAME,
                "SUM($KEY_DURATION) as total_duration",
                "COUNT(*) as enter_count",
                "COUNT($KEY_EXIT_TIME) as exit_count"
            ),
            "$KEY_FENCE_ID = ? AND DATE($KEY_ENTER_TIME/1000, 'unixepoch') = ?",
            arrayOf(fenceId, date),
            null,
            null,
            null
        )

        var stats: DailyFenceStats? = null
        try {
            if (cursor.moveToFirst()) {
                stats = DailyFenceStats(
                    date = date,
                    fenceId = cursor.getString(0),
                    fenceName = cursor.getString(1),
                    totalStayDuration = cursor.getLong(2),
                    enterCount = cursor.getInt(3),
                    exitCount = cursor.getInt(4)
                )
            }
        } finally {
            cursor.close()
        }
        return stats
    }

    private fun pointsToJson(points: List<GeoFencePoint>): String {
        val jsonArray = JSONArray()
        for (point in points) {
            val obj = org.json.JSONObject()
            obj.put("lat", point.latitude)
            obj.put("lng", point.longitude)
            jsonArray.put(obj)
        }
        return jsonArray.toString()
    }

    private fun jsonToPoints(json: String): List<GeoFencePoint> {
        val points = mutableListOf<GeoFencePoint>()
        val jsonArray = JSONArray(json)
        for (i in 0 until jsonArray.length()) {
            val obj = jsonArray.getJSONObject(i)
            points.add(
                GeoFencePoint(
                    latitude = obj.getDouble("lat"),
                    longitude = obj.getDouble("lng")
                )
            )
        }
        return points
    }

    fun exportToCSV(context: Context): String? {
        try {
            val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
            val csvContent = StringBuilder()

            csvContent.append("=== 围栏数据 ===\n")
            csvContent.append("ID,名称,类型,创建时间\n")
            getAllFences().forEach { fence ->
                csvContent.append("${fence.id},${fence.name},${fence.type},${dateFormat.format(fence.createdAt)}\n")
            }

            csvContent.append("\n=== 事件记录 ===\n")
            csvContent.append("事件ID,围栏ID,围栏名称,类型,时间,纬度,经度,精度\n")
            getEventsByTimeRange(Date(0), Date()).forEach { event ->
                csvContent.append("${event.id},${event.fenceId},${event.fenceName},${event.action},${dateFormat.format(event.time)},${event.latitude},${event.longitude},${event.accuracy}\n")
            }

            csvContent.append("\n=== 停留记录 ===\n")
            csvContent.append("围栏ID,围栏名称,进入时间,离开时间,停留时长(秒)\n")
            getAllStayRecords().forEach { record ->
                csvContent.append("${record.fenceId},${record.fenceName},${dateFormat.format(record.enterTime)},${record.exitTime?.let { dateFormat.format(it) } ?: ""},${record.durationSeconds ?: ""}\n")
            }

            val fileName = "GeoFenceExport_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())}.csv"
            val file = java.io.File(context.getExternalFilesDir(null), fileName)
            file.writeText(csvContent.toString())

            Log.d("GeoFenceDB", "CSV导出成功: ${file.absolutePath}")
            return file.absolutePath
        } catch (e: Exception) {
            Log.e("GeoFenceDB", "CSV导出失败", e)
            return null
        }
    }

    fun deleteEventsBefore(beforeDate: Date?): Int {
        val db = writableDatabase
        val deletedCount = if (beforeDate != null) {
            db.delete(TABLE_EVENTS, "$KEY_TIMESTAMP < ?", arrayOf(beforeDate.time.toString()))
        } else {
            db.delete(TABLE_EVENTS, null, null)
        }
        return deletedCount
    }

    fun deleteStaysBefore(beforeDate: Date?): Int {
        val db = writableDatabase
        val deletedCount = if (beforeDate != null) {
            db.delete(TABLE_STAYS, "$KEY_ENTER_TIME < ?", arrayOf(beforeDate.time.toString()))
        } else {
            db.delete(TABLE_STAYS, null, null)
        }
        return deletedCount
    }

    fun getOpenStayRecords(): List<FenceStayRecord> {
        val records = mutableListOf<FenceStayRecord>()
        val db = readableDatabase
        val cursor = db.query(
            TABLE_STAYS,
            null,
            "$KEY_EXIT_TIME IS NULL",
            null,
            null,
            null,
            null
        )

        try {
            if (cursor.moveToFirst()) {
                do {
                    val fenceId = cursor.getString(cursor.getColumnIndexOrThrow(KEY_FENCE_ID))
                    val fenceName = cursor.getString(cursor.getColumnIndexOrThrow(KEY_FENCE_NAME))
                    val enterTime = Date(cursor.getLong(cursor.getColumnIndexOrThrow(KEY_ENTER_TIME)))
                    val exitTime = if (cursor.isNull(cursor.getColumnIndexOrThrow(KEY_EXIT_TIME))) {
                        null
                    } else {
                        Date(cursor.getLong(cursor.getColumnIndexOrThrow(KEY_EXIT_TIME)))
                    }
                    val duration = if (cursor.isNull(cursor.getColumnIndexOrThrow(KEY_DURATION))) {
                        null
                    } else {
                        cursor.getLong(cursor.getColumnIndexOrThrow(KEY_DURATION))
                    }

                    records.add(
                        FenceStayRecord(
                            fenceId = fenceId,
                            fenceName = fenceName,
                            enterTime = enterTime,
                            exitTime = exitTime,
                            durationSeconds = duration
                        )
                    )
                } while (cursor.moveToNext())
            }
        } finally {
            cursor.close()
        }
        return records
    }

    fun saveDailyStayRecord(record: DailyStayRecord) {
        val db = writableDatabase
        val values = ContentValues()
        values.put(KEY_DAILY_DATE, record.date)
        values.put(KEY_DAILY_FENCE_ID, record.fenceId)
        values.put(KEY_DAILY_FENCE_NAME, record.fenceName)
        values.put(KEY_DAILY_DURATION, record.totalDurationSeconds)
        values.put(KEY_DAILY_LAST_UPDATED, record.lastUpdated.time)

        db.insertWithOnConflict(
            TABLE_DAILY_STAYS,
            null,
            values,
            SQLiteDatabase.CONFLICT_REPLACE
        )
        Log.d("GeoFenceDB", "保存日统计: ${record.fenceName}, 日期=${record.date}, 时长=${record.totalDurationSeconds}秒")
    }

    fun getDailyStayRecord(dateKey: String, fenceId: String): DailyStayRecord? {
        val db = readableDatabase
        val cursor = db.query(
            TABLE_DAILY_STAYS,
            null,
            "$KEY_DAILY_DATE = ? AND $KEY_DAILY_FENCE_ID = ?",
            arrayOf(dateKey, fenceId),
            null,
            null,
            null
        )

        var record: DailyStayRecord? = null
        try {
            if (cursor.moveToFirst()) {
                record = DailyStayRecord(
                    date = cursor.getString(cursor.getColumnIndexOrThrow(KEY_DAILY_DATE)),
                    fenceId = cursor.getString(cursor.getColumnIndexOrThrow(KEY_DAILY_FENCE_ID)),
                    fenceName = cursor.getString(cursor.getColumnIndexOrThrow(KEY_DAILY_FENCE_NAME)),
                    totalDurationSeconds = cursor.getLong(cursor.getColumnIndexOrThrow(KEY_DAILY_DURATION)),
                    lastUpdated = Date(cursor.getLong(cursor.getColumnIndexOrThrow(KEY_DAILY_LAST_UPDATED)))
                )
            }
        } finally {
            cursor.close()
        }
        return record
    }

    fun getDailyStayRecordsByDate(dateKey: String): List<DailyStayRecord> {
        val records = mutableListOf<DailyStayRecord>()
        val db = readableDatabase
        val cursor = db.query(
            TABLE_DAILY_STAYS,
            null,
            "$KEY_DAILY_DATE = ?",
            arrayOf(dateKey),
            null,
            null,
            "$KEY_DAILY_DURATION DESC"
        )

        try {
            if (cursor.moveToFirst()) {
                do {
                    records.add(
                        DailyStayRecord(
                            date = cursor.getString(cursor.getColumnIndexOrThrow(KEY_DAILY_DATE)),
                            fenceId = cursor.getString(cursor.getColumnIndexOrThrow(KEY_DAILY_FENCE_ID)),
                            fenceName = cursor.getString(cursor.getColumnIndexOrThrow(KEY_DAILY_FENCE_NAME)),
                            totalDurationSeconds = cursor.getLong(cursor.getColumnIndexOrThrow(KEY_DAILY_DURATION)),
                            lastUpdated = Date(cursor.getLong(cursor.getColumnIndexOrThrow(KEY_DAILY_LAST_UPDATED)))
                        )
                    )
                } while (cursor.moveToNext())
            }
        } finally {
            cursor.close()
        }
        return records
    }

    /** 获取所有日期的每日停留汇总记录（用于导出） */
    fun getAllDailyStayRecords(): List<DailyStayRecord> {
        val records = mutableListOf<DailyStayRecord>()
        val db = readableDatabase
        val cursor = db.query(
            TABLE_DAILY_STAYS,
            null,
            null,
            null,
            null,
            null,
            "$KEY_DAILY_DATE DESC"
        )

        try {
            if (cursor.moveToFirst()) {
                do {
                    records.add(
                        DailyStayRecord(
                            date = cursor.getString(cursor.getColumnIndexOrThrow(KEY_DAILY_DATE)),
                            fenceId = cursor.getString(cursor.getColumnIndexOrThrow(KEY_DAILY_FENCE_ID)),
                            fenceName = cursor.getString(cursor.getColumnIndexOrThrow(KEY_DAILY_FENCE_NAME)),
                            totalDurationSeconds = cursor.getLong(cursor.getColumnIndexOrThrow(KEY_DAILY_DURATION)),
                            lastUpdated = Date(cursor.getLong(cursor.getColumnIndexOrThrow(KEY_DAILY_LAST_UPDATED)))
                        )
                    )
                } while (cursor.moveToNext())
            }
        } finally {
            cursor.close()
        }
        return records
    }

    /** 批量插入或累加每日停留记录（用于导入时的合并） */
    fun upsertDailyStayRecords(records: List<DailyStayRecord>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (record in records) {
                // 先查询是否已存在
                val cursor = db.query(
                    TABLE_DAILY_STAYS,
                    arrayOf(KEY_DAILY_DURATION),
                    "$KEY_DAILY_DATE = ? AND $KEY_DAILY_FENCE_ID = ?",
                    arrayOf(record.date, record.fenceId),
                    null, null, null
                )
                val existingDuration = if (cursor.moveToFirst()) {
                    cursor.getLong(cursor.getColumnIndexOrThrow(KEY_DAILY_DURATION))
                } else 0L
                cursor.close()

                val values = ContentValues().apply {
                    put(KEY_DAILY_DATE, record.date)
                    put(KEY_DAILY_FENCE_ID, record.fenceId)
                    put(KEY_DAILY_FENCE_NAME, record.fenceName)
                    put(KEY_DAILY_DURATION, existingDuration + record.totalDurationSeconds)
                    put(KEY_DAILY_LAST_UPDATED, System.currentTimeMillis())
                }
                db.insertWithOnConflict(
                    TABLE_DAILY_STAYS,
                    null,
                    values,
                    SQLiteDatabase.CONFLICT_REPLACE
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** 批量插入停留记录（使用提供的ID，支持去重） */
    fun insertStayRecordsWithId(records: List<FenceStayRecord>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (record in records) {
                val values = ContentValues().apply {
                    put(KEY_STAY_ID, record.id ?: UUID.randomUUID().toString())
                    put(KEY_FENCE_ID, record.fenceId)
                    put(KEY_FENCE_NAME, record.fenceName)
                    put(KEY_ENTER_TIME, record.enterTime.time)
                    put(KEY_EXIT_TIME, record.exitTime?.time)
                    put(KEY_DURATION, record.durationSeconds)
                }
                db.insertWithOnConflict(
                    TABLE_STAYS,
                    null,
                    values,
                    SQLiteDatabase.CONFLICT_REPLACE
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** 批量插入围栏事件（使用提供的ID，支持去重） */
    fun insertEventsWithId(events: List<GeoFenceEvent>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (event in events) {
                val values = ContentValues().apply {
                    put(KEY_EVENT_ID, event.id)
                    put(KEY_FENCE_ID, event.fenceId)
                    put(KEY_FENCE_NAME, event.fenceName)
                    put(KEY_ACTION, event.action.name)
                    put(KEY_TIMESTAMP, event.time.time)
                    put(KEY_LATITUDE, event.latitude)
                    put(KEY_LONGITUDE, event.longitude)
                    put(KEY_ACCURACY, event.accuracy)
                }
                db.insertWithOnConflict(
                    TABLE_EVENTS,
                    null,
                    values,
                    SQLiteDatabase.CONFLICT_REPLACE
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun getStayRecordsByTimeRange(startTime: Date, endTime: Date): List<FenceStayRecord> {
        val records = mutableListOf<FenceStayRecord>()
        val db = readableDatabase
        val cursor = db.query(
            TABLE_STAYS,
            null,
            "$KEY_ENTER_TIME >= ? AND $KEY_ENTER_TIME <= ?",
            arrayOf(startTime.time.toString(), endTime.time.toString()),
            null,
            null,
            "$KEY_ENTER_TIME DESC"
        )

        try {
            if (cursor.moveToFirst()) {
                do {
                    val fenceId = cursor.getString(cursor.getColumnIndexOrThrow(KEY_FENCE_ID))
                    val fenceName = cursor.getString(cursor.getColumnIndexOrThrow(KEY_FENCE_NAME))
                    val enterTime = Date(cursor.getLong(cursor.getColumnIndexOrThrow(KEY_ENTER_TIME)))
                    val exitTime = if (cursor.isNull(cursor.getColumnIndexOrThrow(KEY_EXIT_TIME))) {
                        null
                    } else {
                        Date(cursor.getLong(cursor.getColumnIndexOrThrow(KEY_EXIT_TIME)))
                    }
                    val duration = if (cursor.isNull(cursor.getColumnIndexOrThrow(KEY_DURATION))) {
                        null
                    } else {
                        cursor.getLong(cursor.getColumnIndexOrThrow(KEY_DURATION))
                    }

                    records.add(
                        FenceStayRecord(
                            fenceId = fenceId,
                            fenceName = fenceName,
                            enterTime = enterTime,
                            exitTime = exitTime,
                            durationSeconds = duration
                        )
                    )
                } while (cursor.moveToNext())
            }
        } finally {
            cursor.close()
        }
        return records
    }

    // ============================================================
    // 围栏导出功能
    // ============================================================

    /**
     * 导出选定的围栏到JSON文件
     * @param fences 要导出的围栏列表
     * @param context Context用于获取外部存储目录
     * @return 导出文件的绝对路径，失败返回null
     */
    fun generateFencesJson(fences: List<GeoFence>): String? {
        try {
            val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
            val jsonArray = JSONArray()

            fences.forEach { fence ->
                val jsonObject = org.json.JSONObject().apply {
                    put("id", UUID.randomUUID().toString()) // 生成新ID避免冲突
                    put("name", fence.name)
                    put("type", fence.type.name)
                    put("color", fence.color)
                    put("alpha", fence.alpha.toDouble())
                    put("categoryId", fence.categoryId)
                    put("createdAt", dateFormat.format(fence.createdAt))

                    // 圆形围栏数据
                    fence.center?.let {
                        put("centerLat", it.latitude)
                        put("centerLng", it.longitude)
                    }
                    fence.radius?.let {
                        put("radius", it.toDouble())
                    }

                    // 多边形围栏数据
                    fence.points?.let { points ->
                        val pointsArray = JSONArray()
                        points.forEach { point ->
                            val pointObj = org.json.JSONObject().apply {
                                put("lat", point.latitude)
                                put("lng", point.longitude)
                            }
                            pointsArray.put(pointObj)
                        }
                        put("points", pointsArray)
                    }
                }
                jsonArray.put(jsonObject)
            }

            val wrapper = org.json.JSONObject().apply {
                put("version", 1)
                put("exportTime", dateFormat.format(Date()))
                put("fenceCount", fences.size)
                put("fences", jsonArray)
            }

            Log.d("GeoFenceDB", "围栏JSON生成成功，共${fences.size}个围栏")
            return wrapper.toString(2)
        } catch (e: Exception) {
            Log.e("GeoFenceDB", "围栏JSON生成失败", e)
            return null
        }
    }

    fun exportFencesToJson(fences: List<GeoFence>, context: Context): String? {
        try {
            val json = generateFencesJson(fences) ?: return null
            val fileName = "TrackMaster_Fences_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())}.json"
            val file = java.io.File(context.getExternalFilesDir(null), fileName)
            file.writeText(json)
            Log.d("GeoFenceDB", "围栏导出成功: ${file.absolutePath}")
            return file.absolutePath
        } catch (e: Exception) {
            Log.e("GeoFenceDB", "围栏导出失败", e)
            return null
        }
    }

    /**
     * 从JSON文件导入围栏
     * @param jsonContent JSON文件内容
     * @param preserveId 是否保留原始 ID（完整备份=true，分享模板=false始终用新ID）
     * @return 成功导入的围栏数量，失败返回-1
     */
    fun importFencesFromJson(jsonContent: String, preserveId: Boolean = false): Int {
        try {
            val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
            val wrapper = org.json.JSONObject(jsonContent)

            // 验证文件格式
            val version = wrapper.optInt("version", 0)
            if (version == 0) {
                Log.e("GeoFenceDB", "无效的围栏文件格式：缺少版本号")
                return -1
            }

            val fencesArray = wrapper.getJSONArray("fences")
            val totalCount = fencesArray.length()
            Log.d("GeoFenceDB", "开始导入围栏，共${totalCount}个")
            var importedCount = 0

            for (i in 0 until totalCount) {
                val fenceJson = fencesArray.getJSONObject(i)

                try {
                    val type = GeoFenceType.valueOf(fenceJson.getString("type"))
                    val center = if (fenceJson.has("centerLat") && fenceJson.has("centerLng")) {
                        GeoFencePoint(
                            latitude = fenceJson.getDouble("centerLat"),
                            longitude = fenceJson.getDouble("centerLng")
                        )
                    } else null

                    val radius = if (fenceJson.has("radius")) {
                        fenceJson.getDouble("radius").toFloat()
                    } else null

                    val points = if (fenceJson.has("points")) {
                        val pointsArray = fenceJson.getJSONArray("points")
                        val pointList = mutableListOf<GeoFencePoint>()
                        for (j in 0 until pointsArray.length()) {
                            val pointObj = pointsArray.getJSONObject(j)
                            pointList.add(GeoFencePoint(
                                latitude = pointObj.getDouble("lat"),
                                longitude = pointObj.getDouble("lng")
                            ))
                        }
                        pointList
                    } else null

                    val createdAt = if (fenceJson.has("createdAt")) {
                        dateFormat.parse(fenceJson.getString("createdAt")) ?: Date()
                    } else Date()

                    val fence = GeoFence(
                        id = if (preserveId) {
                            val existing = fenceJson.optString("id", null)
                            if (existing != null && getFenceById(existing) == null) existing else UUID.randomUUID().toString()
                        } else {
                            UUID.randomUUID().toString()
                        },
                        name = fenceJson.getString("name"),
                        type = type,
                        center = center,
                        radius = radius,
                        points = points,
                        categoryId = fenceJson.optString("categoryId", Category.UNASSIGNED_ID),
                        createdAt = createdAt,
                        color = fenceJson.optString("color", "#FF0000"),
                        alpha = fenceJson.optDouble("alpha", 0.3).toFloat()
                    )

                    val insertResult = insertFence(fence)
                    if (insertResult > 0) {
                        importedCount++
                        Log.d("GeoFenceDB", "导入围栏成功: ${fence.name}, 类型: ${fence.type}, 中心: $center, 半径: $radius")
                    } else {
                        Log.e("GeoFenceDB", "导入围栏失败: ${fence.name}, insert返回: $insertResult")
                    }
                } catch (e: Exception) {
                    Log.e("GeoFenceDB", "导入第${i+1}个围栏失败: ${e.message}")
                }
            }

            Log.d("GeoFenceDB", "围栏导入完成，成功${importedCount}/$totalCount 个")
            return importedCount
        } catch (e: Exception) {
            Log.e("GeoFenceDB", "围栏导入失败", e)
            return -1
        }
    }
}
