package com.nullclass.core.model

/**
 * 大节（连堂）换算：大节 N = 第 2N-1..2N 小节。
 * 例：大节 1 = 第 1..2 节，大节 2 = 第 3..4 节。
 * 大节编号不落库，由节次号派生（见 docs/impl 1.3.1）。
 */
object SectionMath {

    /** 小节号 → 大节号。第 1、2 节 → 大节 1。 */
    fun sectionIndex(periodIndex: Int): Int {
        require(periodIndex >= 1) { "periodIndex must be >= 1, was $periodIndex" }
        return (periodIndex + 1) / 2
    }

    /** 大节号 → 小节范围（含首尾）。 */
    fun sectionPeriodRange(sectionIndex: Int): IntRange {
        require(sectionIndex >= 1) { "sectionIndex must be >= 1, was $sectionIndex" }
        return (2 * sectionIndex - 1)..(2 * sectionIndex)
    }

    /** 总大节数。奇数节次时最后一个大节只含一节。 */
    fun sectionCount(totalPeriods: Int): Int {
        require(totalPeriods >= 1) { "totalPeriods must be >= 1, was $totalPeriods" }
        return (totalPeriods + 1) / 2
    }
}
