package com.nullclass.core.model

/**
 * 课表展示用的**与语言无关**的格式化。
 *
 * 需要文字的部分（星期、周次、节次、倒计时）不在这里 —— 本模块是纯 Kotlin 模块，取不到
 * Android 资源，把中文写死在这里会让文案无法翻译。那些文案统一由 `:core:ui` 的
 * `ScheduleText` 从字符串资源取，详见 `docs/i18n.md`。
 */
object ScheduleFormat {

    /** 0..1439 分钟数 →「8:00」「14:05」。纯数字，不随语言变化。 */
    fun minuteLabel(minuteOfDay: Int): String {
        val hour = minuteOfDay / 60
        val minute = minuteOfDay % 60
        return "$hour:${minute.toString().padStart(2, '0')}"
    }
}
