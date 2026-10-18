package com.trackmaster.app.data.repository

import com.trackmaster.app.TrackMasterApplication
import com.trackmaster.app.model.Category

/**
 * 围栏分类的读写。
 *
 * 和 [FenceRepository] / [SessionRepository] 一样是薄委托——真正的逻辑在
 * `CategoryManager` 里。这层存在的意义是让 ViewModel 不直接摸
 * `TrackMasterApplication` 单例。
 */
interface CategoryRepository {

    /** 全部分类，按 sortOrder 排好序。 */
    suspend fun getAllCategories(): List<Category>

    /** 把某个围栏归到某个分类下。 */
    suspend fun moveFenceToCategory(fenceId: String, categoryId: String): Result<Unit>
}

class AppCategoryRepository(
    private val app: TrackMasterApplication
) : CategoryRepository {

    override suspend fun getAllCategories(): List<Category> =
        app.categoryManager.getAllCategories()

    override suspend fun moveFenceToCategory(
        fenceId: String,
        categoryId: String
    ): Result<Unit> = app.categoryManager.moveFenceToCategory(fenceId, categoryId)
}
