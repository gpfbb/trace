package com.trackmaster.app

import java.util.Date
import java.util.UUID

data class Category(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val icon: String? = null,
    val sortOrder: Int = 0,
    val createdAt: Date = Date()
) {
    companion object {
        const val UNASSIGNED_ID = "unassigned"
        val UNASSIGNED = Category(
            id = UNASSIGNED_ID,
            name = "未分类"
        )
    }
}
