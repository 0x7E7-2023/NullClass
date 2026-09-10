package com.nullclass.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CourseColorKeywordsTest {

    @Test
    fun `公共课按领域落档`() {
        assertEquals(CourseColorKeywords.MATH, CourseColorKeywords.match("高等数学A（一）"))
        assertEquals(CourseColorKeywords.LANGUAGE, CourseColorKeywords.match("综合英语（一）"))
        assertEquals(CourseColorKeywords.SPORT, CourseColorKeywords.match("体育（一）"))
        assertEquals(CourseColorKeywords.SPORT, CourseColorKeywords.match("基础体育"))
        assertEquals(CourseColorKeywords.POLITICS, CourseColorKeywords.match("思想道德与法治"))
        assertEquals(CourseColorKeywords.POLITICS, CourseColorKeywords.match("形势与政策"))
        assertEquals(CourseColorKeywords.CS, CourseColorKeywords.match("C语言程序设计"))
        assertEquals(CourseColorKeywords.CS, CourseColorKeywords.match("C 语言程序设计"))
        assertEquals(CourseColorKeywords.GENERAL, CourseColorKeywords.match("大学生心理健康"))
        assertEquals(CourseColorKeywords.GENERAL, CourseColorKeywords.match("职业生涯与发展规划"))
        assertEquals(CourseColorKeywords.MILITARY_LAW, CourseColorKeywords.match("军事理论"))
        assertEquals(CourseColorKeywords.CHEMISTRY, CourseColorKeywords.match("化学原理B"))
        assertEquals(CourseColorKeywords.PHYSICS, CourseColorKeywords.match("大学物理"))
    }

    @Test
    fun `跨档时更长的关键词获胜`() {
        assertEquals(CourseColorKeywords.POLITICS, CourseColorKeywords.match("国家安全教育"))
        assertEquals(CourseColorKeywords.POLITICS, CourseColorKeywords.match("思想道德与法治实践"))
        assertEquals(CourseColorKeywords.PHYSICS, CourseColorKeywords.match("大学物理实验"))
        assertEquals(CourseColorKeywords.CHEMISTRY, CourseColorKeywords.match("物理化学"))
        assertEquals(CourseColorKeywords.MATH, CourseColorKeywords.match("离散数学"))
        assertEquals(CourseColorKeywords.MATH, CourseColorKeywords.match("概率论与数理统计"))
    }

    @Test
    fun `英文课名忽略大小写`() {
        assertEquals(CourseColorKeywords.LANGUAGE, CourseColorKeywords.match("College English"))
        assertEquals(CourseColorKeywords.CS, CourseColorKeywords.match("python程序设计"))
    }

    @Test
    fun `未命中不猜`() {
        assertNull(CourseColorKeywords.match(""))
        assertNull(CourseColorKeywords.match("   "))
        assertNull(CourseColorKeywords.match("机械制图"))
        assertNull(CourseColorKeywords.match("信号检测"))
        assertNull(CourseColorKeywords.match("C0"))
    }
}
