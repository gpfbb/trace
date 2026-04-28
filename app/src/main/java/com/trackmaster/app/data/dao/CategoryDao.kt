package com.trackmaster.app.data.dao

import android.content.ContentValues
import com.trackmaster.app.data.database.DatabaseManager
import com.trackmaster.app.model.Category
import com.trackmaster.app.utils.AppLog
import java.util.Date
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

class CategoryDao(private val dbManager: DatabaseManager) {

    companion object {
        private const val TAG = "CategoryDao"
    }

    private val categoryCache = ConcurrentHashMap<String, Category>()
    private val allCategoriesCache = CopyOnWriteArrayList<Category>()
    private val allCategoriesCacheValid = java.util.concurrent.atomic.AtomicBoolean(false)

    fun invalidateCache() {
        categoryCache.clear()
        allCategoriesCache.clear()
        allCategoriesCacheValid.set(false)
        AppLog.d(TAG, "分类缓存已失效")
    }

    fun insertCategory(category: Category): Long {
        invalidateCache()
        val db = dbManager.writableDatabase
        val values = ContentValues()
        values.put(DatabaseManager.KEY_CATEGORY_TABLE_ID, category.id)
        values.put(DatabaseManager.KEY_CATEGORY_NAME, category.name)
        values.put(DatabaseManager.KEY_CATEGORY_ICON, category.icon)
        values.put(DatabaseManager.KEY_CATEGORY_SORT_ORDER, category.sortOrder)
        values.put(DatabaseManager.KEY_CATEGORY_CREATED_AT, category.createdAt.time)
        return db.insert(DatabaseManager.TABLE_CATEGORIES, null, values)
    }

    fun getAllCategories(): List<Category> {
        if (allCategoriesCacheValid.get()) {
            return ArrayList(allCategoriesCache)
        }
        val categories = mutableListOf<Category>()
        val db = dbManager.readableDatabase
        val cursor = db.query(
            DatabaseManager.TABLE_CATEGORIES, null, null, null, null, null,
            "${DatabaseManager.KEY_CATEGORY_SORT_ORDER} ASC, ${DatabaseManager.KEY_CATEGORY_CREATED_AT} ASC"
        )
        try {
            if (cursor.moveToFirst()) {
                do {
                    val category = Category(
                        id = cursor.getString(cursor.getColumnIndexOrThrow(DatabaseManager.KEY_CATEGORY_TABLE_ID)),
                        name = cursor.getString(cursor.getColumnIndexOrThrow(DatabaseManager.KEY_CATEGORY_NAME)),
                        icon = if (cursor.getColumnIndex(DatabaseManager.KEY_CATEGORY_ICON) >= 0 && !cursor.isNull(cursor.getColumnIndexOrThrow(DatabaseManager.KEY_CATEGORY_ICON)))
                            cursor.getString(cursor.getColumnIndexOrThrow(DatabaseManager.KEY_CATEGORY_ICON)) else null,
                        sortOrder = if (cursor.getColumnIndex(DatabaseManager.KEY_CATEGORY_SORT_ORDER) >= 0)
                            cursor.getInt(cursor.getColumnIndexOrThrow(DatabaseManager.KEY_CATEGORY_SORT_ORDER)) else 0,
                        createdAt = Date(cursor.getLong(cursor.getColumnIndexOrThrow(DatabaseManager.KEY_CATEGORY_CREATED_AT)))
                    )
                    categories.add(category)
                    categoryCache[category.id] = category
                } while (cursor.moveToNext())
            }
        } finally {
            cursor.close()
        }
        allCategoriesCache.clear()
        allCategoriesCache.addAll(categories)
        allCategoriesCacheValid.set(true)
        return categories
    }

    fun updateCategory(category: Category): Int {
        invalidateCache()
        val db = dbManager.writableDatabase
        val values = ContentValues()
        values.put(DatabaseManager.KEY_CATEGORY_NAME, category.name)
        values.put(DatabaseManager.KEY_CATEGORY_ICON, category.icon)
        values.put(DatabaseManager.KEY_CATEGORY_SORT_ORDER, category.sortOrder)
        return db.update(DatabaseManager.TABLE_CATEGORIES, values, "${DatabaseManager.KEY_CATEGORY_TABLE_ID} = ?", arrayOf(category.id))
    }

    fun deleteCategory(categoryId: String): Int {
        invalidateCache()
        val db = dbManager.writableDatabase
        return db.delete(DatabaseManager.TABLE_CATEGORIES, "${DatabaseManager.KEY_CATEGORY_TABLE_ID} = ?", arrayOf(categoryId))
    }

    fun getCategoryByName(name: String): Category? {
        categoryCache.values.find { it.name == name }?.let { return it }
        val db = dbManager.readableDatabase
        val cursor = db.query(
            DatabaseManager.TABLE_CATEGORIES, null,
            "${DatabaseManager.KEY_CATEGORY_NAME} = ?", arrayOf(name),
            null, null, null
        )
        var category: Category? = null
        try {
            if (cursor.moveToFirst()) {
                category = Category(
                    id = cursor.getString(cursor.getColumnIndexOrThrow(DatabaseManager.KEY_CATEGORY_TABLE_ID)),
                    name = cursor.getString(cursor.getColumnIndexOrThrow(DatabaseManager.KEY_CATEGORY_NAME)),
                    icon = if (cursor.getColumnIndex(DatabaseManager.KEY_CATEGORY_ICON) >= 0 && !cursor.isNull(cursor.getColumnIndexOrThrow(DatabaseManager.KEY_CATEGORY_ICON)))
                        cursor.getString(cursor.getColumnIndexOrThrow(DatabaseManager.KEY_CATEGORY_ICON)) else null,
                    sortOrder = if (cursor.getColumnIndex(DatabaseManager.KEY_CATEGORY_SORT_ORDER) >= 0)
                        cursor.getInt(cursor.getColumnIndexOrThrow(DatabaseManager.KEY_CATEGORY_SORT_ORDER)) else 0,
                    createdAt = Date(cursor.getLong(cursor.getColumnIndexOrThrow(DatabaseManager.KEY_CATEGORY_CREATED_AT)))
                )
                categoryCache[category.id] = category
            }
        } finally {
            cursor.close()
        }
        return category
    }

    fun getCategoryById(id: String): Category? {
        categoryCache[id]?.let { return it }
        val db = dbManager.readableDatabase
        val cursor = db.query(
            DatabaseManager.TABLE_CATEGORIES, null,
            "${DatabaseManager.KEY_CATEGORY_TABLE_ID} = ?", arrayOf(id),
            null, null, null
        )
        var category: Category? = null
        try {
            if (cursor.moveToFirst()) {
                category = Category(
                    id = cursor.getString(cursor.getColumnIndexOrThrow(DatabaseManager.KEY_CATEGORY_TABLE_ID)),
                    name = cursor.getString(cursor.getColumnIndexOrThrow(DatabaseManager.KEY_CATEGORY_NAME)),
                    icon = if (cursor.getColumnIndex(DatabaseManager.KEY_CATEGORY_ICON) >= 0 && !cursor.isNull(cursor.getColumnIndexOrThrow(DatabaseManager.KEY_CATEGORY_ICON)))
                        cursor.getString(cursor.getColumnIndexOrThrow(DatabaseManager.KEY_CATEGORY_ICON)) else null,
                    sortOrder = if (cursor.getColumnIndex(DatabaseManager.KEY_CATEGORY_SORT_ORDER) >= 0)
                        cursor.getInt(cursor.getColumnIndexOrThrow(DatabaseManager.KEY_CATEGORY_SORT_ORDER)) else 0,
                    createdAt = Date(cursor.getLong(cursor.getColumnIndexOrThrow(DatabaseManager.KEY_CATEGORY_CREATED_AT)))
                )
                categoryCache[category.id] = category
            }
        } finally {
            cursor.close()
        }
        return category
    }
}
