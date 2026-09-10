package com.nullclass.core.model

/**
 * 教务导入按课名关键词落到 [Course.colorIndex]，与 :core:ui `CoursePalette` 12 档对齐。
 *
 * 不加领域字段：只在归一化时写入下标。手动改色、再导入对齐仍走本地已有的
 * [Course.colorIndex]。命中规则：去掉空白后忽略大小写做子串匹配，取最长关键词；
 * 同长度取更靠前的档。未命中由调用方顺序轮转，避免专业课全挤在同一色。
 */
object CourseColorKeywords {

    const val SIZE = 12

    /** 红 · 思政 */
    const val POLITICS = 0

    /** 玫红 · 心理 / 生涯 / 通识人文 */
    const val GENERAL = 1

    /** 紫 · 计算机 */
    const val CS = 2

    /** 深紫 · 电子 / 自动化 */
    const val EE = 3

    /** 靛蓝 · 物理 */
    const val PHYSICS = 4

    /** 蓝 · 外语 */
    const val LANGUAGE = 5

    /** 绿 · 体育 */
    const val SPORT = 6

    /** 深绿 · 实训 / 设计 */
    const val PRACTICE = 7

    /** 黄绿 · 经管 */
    const val BUSINESS = 8

    /** 橙 · 数学 */
    const val MATH = 9

    /** 深橙 · 化学 / 材料 */
    const val CHEMISTRY = 10

    /** 棕 · 军事 / 法学 */
    const val MILITARY_LAW = 11

    /**
     * 每档关键词。跨档冲突靠更长的词拆开（「物理化学」进化学，「国家安全」进思政）。
     * 不放过于宽的单字或「概论 / 原理 / 教育 / 实验 / 实践」。
     */
    private val BANKS: Array<Array<String>> = arrayOf(
        // 0 红 思政
        arrayOf(
            "思想道德", "思修", "马克思主义", "马原", "毛泽东", "毛概",
            "习近平", "习概", "中国近现代史纲要", "中国近现代史", "史纲",
            "形势与政策", "形势政策", "思想政治", "思政", "国家安全",
            "中国特色社会主义", "近代史",
        ),
        // 1 玫红 通识
        arrayOf(
            "心理健康", "心理", "职业生涯", "职业规划", "就业指导", "创新创业",
            "大学语文", "应用文写作", "美育", "艺术鉴赏", "音乐鉴赏", "劳动教育",
        ),
        // 2 紫 计算机
        arrayOf(
            "程序设计", "编程", "C语言", "Java", "Python",
            "计算机网络", "计算机组成", "计算机", "软件工程",
            "数据结构", "操作系统", "数据库", "编译原理",
            "人工智能", "机器学习", "面向对象", "算法",
        ),
        // 3 深紫 电子
        arrayOf(
            "电路分析", "模拟电子", "数字电子", "电子技术", "信号与系统",
            "通信原理", "单片机", "嵌入式", "微机原理", "自动控制",
            "数字逻辑", "电磁场", "电路",
        ),
        // 4 靛蓝 物理
        arrayOf(
            "大学物理", "普通物理", "物理实验", "电磁学", "量子力学",
            "量子物理", "理论力学", "光学", "热学", "物理",
        ),
        // 5 蓝 外语
        arrayOf(
            "大学英语", "综合英语", "专业英语", "College English", "English",
            "英语", "日语", "德语", "法语", "俄语", "韩语", "西班牙语",
            "视听说", "第二外语",
        ),
        // 6 绿 体育
        arrayOf(
            "大学体育", "基础体育", "体育",
            "篮球", "足球", "排球", "网球", "羽毛球", "乒乓球",
            "游泳", "武术", "健美操", "田径", "瑜伽", "跆拳道", "太极拳",
        ),
        // 7 深绿 实践
        arrayOf(
            "课程设计", "毕业设计", "金工实习", "生产实习", "认识实习",
            "毕业实习", "实训", "上机", "实习",
        ),
        // 8 黄绿 经管
        arrayOf(
            "微观经济", "宏观经济", "经济学", "管理学",
            "会计学", "会计", "金融学", "市场营销", "财务管理", "统计学",
        ),
        // 9 橙 数学
        arrayOf(
            "高等数学", "线性代数", "概率论", "数理统计", "微积分",
            "数学分析", "高等代数", "复变函数", "离散数学", "数值分析",
            "运筹学", "微分方程", "工程数学", "数学建模", "数学",
        ),
        // 10 深橙 化学
        arrayOf(
            "物理化学", "无机化学", "有机化学", "分析化学",
            "化学原理", "化学实验", "材料科学", "化学",
        ),
        // 11 棕 军事 / 法学
        arrayOf(
            "军事理论", "国防教育", "军训", "军理", "军事",
            "法律基础", "法学", "宪法",
        ),
    )

    init {
        check(BANKS.size == SIZE)
    }

    /** 命中则返回色板下标，未命中返回 null。 */
    fun match(name: String): Int? {
        val folded = fold(name)
        if (folded.isEmpty()) return null
        var bestIndex: Int? = null
        var bestLen = 0
        BANKS.forEachIndexed { index, keywords ->
            for (keyword in keywords) {
                val needle = fold(keyword)
                if (needle.length > bestLen && folded.contains(needle, ignoreCase = true)) {
                    bestLen = needle.length
                    bestIndex = index
                }
            }
        }
        return bestIndex
    }

    private fun fold(text: String): String = buildString(text.length) {
        for (ch in text) {
            if (!ch.isWhitespace()) append(ch)
        }
    }
}
