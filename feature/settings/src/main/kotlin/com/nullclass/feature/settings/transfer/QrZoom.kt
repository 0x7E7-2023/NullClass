package com.nullclass.feature.settings.transfer

/** 每次最多拉近 15%，自动模式最多 3 倍；手动缩放仍可用相机的完整范围。 */
internal fun nextAutoZoom(current: Float, maximum: Float, fill: Float): Float {
    if (fill !in 0.04f..0.5f) return current
    val target = minOf(current * 0.62f / fill, current * 1.15f, maximum, 3f)
    return if (target > current * 1.02f) target else current
}
