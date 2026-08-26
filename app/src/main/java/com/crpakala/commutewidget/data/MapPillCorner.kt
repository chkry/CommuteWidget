package com.crpakala.commutewidget.data

/** Which corner of the widget's map hosts the overlay pill stack (leave-by, best departure). */
enum class MapPillCorner {
    TOP_START,
    TOP_END,
    BOTTOM_START,
    BOTTOM_END,
}

fun parseMapPillCorner(stored: String?, default: MapPillCorner = MapPillCorner.TOP_START): MapPillCorner {
    if (stored.isNullOrBlank()) {
        return default
    }
    return enumValues<MapPillCorner>().firstOrNull { it.name == stored } ?: default
}
