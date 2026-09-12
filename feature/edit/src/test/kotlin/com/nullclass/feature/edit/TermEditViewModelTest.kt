package com.nullclass.feature.edit

import com.nullclass.core.model.MINUTES_PER_DAY
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * 节次时间那一列文本与分钟数的往返。
 *
 * 这里有两条独立的校验路径：快速设定写回的文本、保存时的解析。两边对「什么算合法」必须一致，
 * 否则会出现「套用成功、保存永远过不了」的死角（`24:00` 就是这么栽的）。
 */
class TermEditViewModelTest {

    @Test
    fun `分钟数与文本能往返`() {
        listOf(0, 1, 8 * 60, 8 * 60 + 5, 22 * 60, MINUTES_PER_DAY - 1, MINUTES_PER_DAY)
            .forEach { minute ->
                val text = TermEditViewModel.minuteLabel(minute)
                assertEquals(minute, TermEditViewModel.parseMinute(text), "往返：$minute（$text）")
            }
    }

    @Test
    fun `认 24 点整，不认越界与垃圾`() {
        // 1440 是模型允许的上界（当天最后一刻），快速设定排到头时写出来的正是 24:00
        assertEquals(MINUTES_PER_DAY, TermEditViewModel.parseMinute("24:00"))
        assertNull(TermEditViewModel.parseMinute("24:01"))
        assertNull(TermEditViewModel.parseMinute("25:00"))
        assertNull(TermEditViewModel.parseMinute("8:60"))
        assertNull(TermEditViewModel.parseMinute("8:0"))
        assertNull(TermEditViewModel.parseMinute("abc"))
        assertNull(TermEditViewModel.parseMinute(""))
    }
}
