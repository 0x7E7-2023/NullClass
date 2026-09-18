package com.nullclass.widget

/** 返回 null 表示忽略边界点击或已经过期的页面事件。由 DataStore 事务内调用。 */
internal fun resolveWidgetPageRequest(
    storedPage: Int,
    storedKey: String?,
    sourcePage: Int,
    targetPage: Int,
    pageCount: Int,
    requestKey: String,
    enabled: Boolean,
): Int? {
    if (!enabled || pageCount < 1) return null
    if (sourcePage !in 0 until pageCount || targetPage !in 0 until pageCount) return null
    if (kotlin.math.abs(targetPage.toLong() - sourcePage) != 1L) return null
    val currentPage = if (storedKey == requestKey) storedPage else 0
    return targetPage.takeIf { currentPage == sourcePage }
}
