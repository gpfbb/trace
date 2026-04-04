package com.trackmaster.app

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class CategoryManager(private val context: Context) {
    private val dbHelper = (context.applicationContext as TrackMasterApplication).dbHelper
    private val TAG = "CategoryManager"

    suspend fun getAllCategories(): List<Category> = withContext(Dispatchers.IO) {
        try {
            dbHelper.getAllCategories()
        } catch (e: Exception) {
            Log.e(TAG, "获取分类列表失败", e)
            emptyList()
        }
    }

    suspend fun createCategory(name: String, icon: String? = null): Result<Category> = withContext(Dispatchers.IO) {
        try {
            if (name.isBlank()) {
                return@withContext Result.failure(IllegalArgumentException("分类名称不能为空"))
            }

            if (dbHelper.getCategoryByName(name) != null) {
                return@withContext Result.failure(IllegalArgumentException("分类名称已存在"))
            }

            val categories = dbHelper.getAllCategories()
            val sortOrder = if (categories.isNotEmpty()) {
                categories.maxOf { it.sortOrder } + 1
            } else {
                0
            }

            val category = Category(
                name = name,
                icon = icon,
                sortOrder = sortOrder
            )

            val result = dbHelper.insertCategory(category)
            if (result != -1L) {
                Result.success(category)
            } else {
                Result.failure(Exception("创建分类失败"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "创建分类失败", e)
            Result.failure(e)
        }
    }

    suspend fun updateCategory(category: Category): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            if (category.name.isBlank()) {
                return@withContext Result.failure(IllegalArgumentException("分类名称不能为空"))
            }

            val existing = dbHelper.getCategoryByName(category.name)
            if (existing != null && existing.id != category.id) {
                return@withContext Result.failure(IllegalArgumentException("分类名称已存在"))
            }

            val result = dbHelper.updateCategory(category)
            if (result > 0) {
                Result.success(Unit)
            } else {
                Result.failure(Exception("更新分类失败"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "更新分类失败", e)
            Result.failure(e)
        }
    }

    suspend fun deleteCategory(categoryId: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            if (categoryId == Category.UNASSIGNED_ID) {
                return@withContext Result.failure(IllegalArgumentException("不能删除未分类"))
            }

            dbHelper.moveFencesToCategory(categoryId, Category.UNASSIGNED_ID)

            val result = dbHelper.deleteCategory(categoryId)
            if (result > 0) {
                Result.success(Unit)
            } else {
                Result.failure(Exception("删除分类失败"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "删除分类失败", e)
            Result.failure(e)
        }
    }

    suspend fun moveFenceToCategory(fenceId: String, categoryId: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val result = dbHelper.updateFenceCategory(fenceId, categoryId)
            if (result > 0) {
                Result.success(Unit)
            } else {
                Result.failure(Exception("移动围栏失败"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "移动围栏失败", e)
            Result.failure(e)
        }
    }

    suspend fun getFencesByCategory(categoryId: String): List<GeoFence> = withContext(Dispatchers.IO) {
        try {
            dbHelper.getFencesByCategory(categoryId)
        } catch (e: Exception) {
            Log.e(TAG, "获取分类下围栏失败", e)
            emptyList()
        }
    }

    suspend fun getFencesGroupedByCategory(): Map<Category, List<GeoFence>> = withContext(Dispatchers.IO) {
        try {
            val categories = dbHelper.getAllCategories()
            val allFences = dbHelper.getAllFences()
            
            val result = mutableMapOf<Category, List<GeoFence>>()
            
            val unassignedFences = allFences.filter { it.categoryId == Category.UNASSIGNED_ID }
            if (unassignedFences.isNotEmpty()) {
                result[Category.UNASSIGNED] = unassignedFences
            }
            
            for (category in categories) {
                val fences = allFences.filter { it.categoryId == category.id }
                if (fences.isNotEmpty()) {
                    result[category] = fences
                }
            }
            
            result
        } catch (e: Exception) {
            Log.e(TAG, "获取分组围栏失败", e)
            emptyMap()
        }
    }

    fun getDailyCategoryStats(date: String): Map<Category, Long> {
        return try {
            val categories = dbHelper.getAllCategories()
            val dailyStats = dbHelper.getDailyStats(date)
            val fenceMap = dbHelper.getAllFences().associateBy { it.id }
            
            val result = mutableMapOf<Category, Long>()
            
            for ((fenceId, duration) in dailyStats) {
                val fence = fenceMap[fenceId]
                val categoryId = fence?.categoryId ?: Category.UNASSIGNED_ID
                
                val category = if (categoryId == Category.UNASSIGNED_ID) {
                    Category.UNASSIGNED
                } else {
                    categories.find { it.id == categoryId } ?: Category.UNASSIGNED
                }
                
                result[category] = (result[category] ?: 0L) + duration
            }
            
            result
        } catch (e: Exception) {
            Log.e(TAG, "获取分类统计失败", e)
            emptyMap()
        }
    }

    companion object {
        val CATEGORY_ICONS = listOf(
            "🏠", "🏢", "🏫", "🏪", "🏥",
            "🏛️", "🎯", "📍", "⭐", "❤️"
        )
    }
}
