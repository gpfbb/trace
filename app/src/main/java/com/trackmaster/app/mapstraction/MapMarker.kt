package com.trackmaster.app.mapstraction

/**
 * 标注点模型
 */
data class MapMarker(
    val id: String,
    val position: MapLocation,
    val title: String = "",
    val snippet: String = "",
    val icon: MarkerIcon = MarkerIcon.DEFAULT,
    val anchor: Pair<Float, Float> = Pair(0.5f, 1.0f)
)

enum class MarkerIcon {
    DEFAULT,
    START,
    END,
    CENTER
}
