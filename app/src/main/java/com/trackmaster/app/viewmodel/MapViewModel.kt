package com.trackmaster.app.viewmodel

import androidx.lifecycle.ViewModel
import com.trackmaster.app.data.GeoFence
import com.trackmaster.app.data.repository.FenceRepository

class MapViewModel(
    private val fenceRepo: FenceRepository
) : ViewModel() {

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
}
