package com.trackmaster.app.ui.theme

import androidx.compose.runtime.Composable
import com.trackmaster.app.ui.design.TrackMasterDesignTheme

/**
 * 兼容入口。
 *
 * 真正的主题实现在 [com.trackmaster.app.ui.design.TrackMasterDesignTheme]。
 * 保留这个名字是因为 `MainActivity` 和可能存在的 Preview 仍在调用它。
 */
@Composable
fun TrackMasterTheme(
    content: @Composable () -> Unit
) {
    TrackMasterDesignTheme(content = content)
}
