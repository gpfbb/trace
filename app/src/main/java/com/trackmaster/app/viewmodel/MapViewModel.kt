package com.trackmaster.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.trackmaster.app.data.GeoFence
import com.trackmaster.app.data.repository.FenceRepository
import com.trackmaster.app.data.repository.CategoryRepository
import com.trackmaster.app.model.Category
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class MapViewModel(
    private val fenceRepo: FenceRepository,
    private val categoryRepo: CategoryRepository
) : ViewModel() {

    private val _categories = MutableStateFlow<List<Category>>(emptyList())

    /** 围栏分类列表，给新建/编辑弹窗里的分类选择器用。 */
    val categories: StateFlow<List<Category>> = _categories.asStateFlow()

    init {
        refreshCategories()
    }

    /** 重新读分类。在分类管理页改完之后回到地图时也应调一次。 */
    fun refreshCategories() {
        viewModelScope.launch {
            _categories.value = categoryRepo.getAllCategories()
        }
    }

    fun addFence(fence: GeoFence) {
        fenceRepo.allFences.add(fence)
        fenceRepo.insertFence(fence)
        fenceRepo.refreshEngineFences()
        fenceRepo.checkAndUpdateFenceState()
    }

    fun saveFence(fence: GeoFence): Boolean {
        val result = fenceRepo.updateFence(fence) > 0
        if (result) {
            val index = fenceRepo.allFences.indexOfFirst { it.id == fence.id }
            if (index >= 0) fenceRepo.allFences[index] = fence
            fenceRepo.refreshEngineFences()
            fenceRepo.checkAndUpdateFenceState()
        }
        return result
    }

    fun deleteFence(fenceId: String) {
        fenceRepo.allFences.removeIf { it.id == fenceId }
        fenceRepo.deleteFence(fenceId)
        fenceRepo.refreshEngineFences()
        fenceRepo.checkAndUpdateFenceState()
    }

    /**
     * 给所有围栏随机分配配色。
     *
     * 原实现写在地图页的批量工具弹窗里，直接调 `app.dbHelper` 并手工
     * 清空/重建 `app.geoFences`——绕过了 Repository 和 Engine 的刷新时机。
     */
    fun applyRandomColors(palette: List<String>) {
        if (palette.isEmpty()) return
        val rng = kotlin.random.Random.Default
        for (fence in fenceRepo.allFences.toList()) {
            val newColor = palette[rng.nextInt(palette.size)]
            fenceRepo.updateFence(fence.copy(color = newColor))
        }
        afterBulkUpdate()
    }

    /** 把所有围栏的填充透明度统一改成 [alpha]。 */
    fun applyGlobalAlpha(alpha: Float) {
        fenceRepo.updateAllFencesAlpha(alpha)
        afterBulkUpdate()
    }

    /**
     * 批量写入之后的统一收尾：重新读库 → 刷新 Engine。
     *
     * 顺序不能反：Engine 的围栏快照是从内存列表构建的，必须先把
     * 内存列表换成数据库里的最新值，再让 Engine 重建。
     */
    private fun afterBulkUpdate() {
        fenceRepo.reloadFencesFromDatabase()
        fenceRepo.refreshEngineFences()
        fenceRepo.checkAndUpdateFenceState()
    }
}
