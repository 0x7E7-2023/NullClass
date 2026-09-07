package com.nullclass.feature.schedule

import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import kotlinx.coroutines.delay
import java.time.LocalTime

/** 当前分钟数（0..1439），每 30 秒刷新，驱动「当前时间线」与今日页进行中标记；首帧前为 -1（视为跨度外）。 */
@Composable
internal fun rememberNowMinute(): Int {
    val state = produceState(-1) {
        while (true) {
            val now = LocalTime.now()
            value = now.hour * 60 + now.minute
            delay(30_000)
        }
    }
    return state.value
}
