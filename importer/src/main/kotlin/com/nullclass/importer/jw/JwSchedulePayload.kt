package com.nullclass.importer.jw

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.time.LocalDate

/**
 * 课表载荷：适配器（或 OCR 结构层）输出的**人类友好** JSON。
 *
 * 与线上 `ScheduleDocument` 解耦：作者不写 UUID、不写时间戳、不写颜色、不写默认节次表，
 * 这些由 [JwScheduleNormalizer] 补齐。
 */
@Serializable
data class JwSchedulePayload(
    val specVersion: Int = JwManifest.SPEC_VERSION,
    /** "schedule"（默认）| "image" | "boxes"。 */
    val kind: String = KIND_SCHEDULE,
    /**
     * 由图片识别生成的载荷。
     *
     * 应用会在导入预览里追加一条固定的核对提示（见 [reviewNotes]）—— 这个承诺写在这里
     * 就得真兑现，别只留个没人读的字段。
     */
    val ocrAssisted: Boolean = false,
    /**
     * 适配器要用户**重点核对**的说明，逐条原样显示在导入预览里。
     *
     * 给「有些字段只能推算」的适配器用：拿不到开学日期的学校占一多半，推算出来的
     * 第 1 周日期会一路影响「现在第几周」、今日页、提醒与小组件，猜错了整个学期的课都错位。
     * 适配器在这里如实说明，用户才有机会在导入前发现。
     */
    val warnings: List<String> = emptyList(),
    val terms: List<JwTerm> = emptyList(),
    val images: List<JwImageRef> = emptyList(),
    /** [KIND_BOXES]：页面文本块（CSS 像素，左上原点）。 */
    val boxes: List<JwTextBox> = emptyList(),
    /** [KIND_BOXES]：文本块所在区域的尺寸；缺省时按文本块的外接框推断。 */
    val pageWidth: Int? = null,
    val pageHeight: Int? = null,
) {
    companion object {
        const val KIND_SCHEDULE = "schedule"
        const val KIND_IMAGE = "image"

        /**
         * 页面文本块：适配器自己量出来的「文字 + 坐标」，应用用表格结构层还原课表。
         *
         * 给「不认学校」的通用适配器用：它不做任何页面结构假设，只把页面读成一张文本框清单，
         * 之后的行列还原与 OCR 课表**共用同一套算法**（[com.nullclass.importer.jw.ocr.JwTableAligner]），
         * 区别只在文本框是量出来的（文字精确）还是 OCR 认出来的。
         */
        const val KIND_BOXES = "boxes"

        /** 单次载荷允许的文本块数量上限。 */
        const val MAX_BOXES = 20_000

        /** 单个文本块的长度上限（超出部分由适配器自己截断）。 */
        const val MAX_BOX_TEXT = 120

        /** [JwSchedulePayload.warnings] 的条数上限。 */
        const val MAX_WARNINGS = 20

        /** 单条 [JwSchedulePayload.warnings] 的长度上限。 */
        const val MAX_WARNING_TEXT = 200
    }

    /**
     * 真正要显示给用户的核对提示：图片识别那条固定说明 + 适配器自己写的。
     *
     * `ocrAssisted` 的语义就落在这里一处，不让每个调用方各写一遍。
     */
    val reviewNotes: List<String>
        get() = (if (ocrAssisted) listOf(OCR_REVIEW_NOTE) else emptyList()) + warnings
}

/** `ocrAssisted` 对应的固定提示（规范 §4）。 */
const val OCR_REVIEW_NOTE = "该课表由图片识别生成，请重点核对课程名、周次与节次"

/** 一个页面文本块：坐标与尺寸都是 CSS 像素。 */
@Serializable
data class JwTextBox(
    val text: String,
    val x: Int,
    val y: Int,
    val w: Int = 0,
    val h: Int = 0,
)

@Serializable
data class JwTerm(
    val name: String,
    /** ISO 日期（yyyy-MM-dd）或 [firstDayEpochDay] 二选一。 */
    val firstDay: String? = null,
    val firstDayEpochDay: Long? = null,
    val totalWeeks: Int = 20,
    val periodTimes: List<JwPeriodTime> = emptyList(),
    val courses: List<JwCourse> = emptyList(),
)

/** "08:00" / "08:00:00" 两种写法都接受。 */
@Serializable
data class JwPeriodTime(
    val periodIndex: Int,
    val start: String,
    val end: String,
)

@Serializable
data class JwCourse(
    val name: String,
    val teacher: String? = null,
    val note: String? = null,
    val blocks: List<JwBlock> = emptyList(),
)

@Serializable
data class JwBlock(
    val dayOfWeek: Int,
    val startPeriod: Int,
    val endPeriod: Int = startPeriod,
    val startWeek: Int = 1,
    val endWeek: Int = 20,
    /** "ALL" | "ODD" | "EVEN" */
    val weekType: String = "ALL",
    val location: String? = null,
)

@Serializable
data class JwImageRef(
    /** http(s) 图片地址，宿主带 WebView Cookie 下载。 */
    val url: String? = null,
    /** base64 data URL（小图 / 适配器从页面 canvas 直接取到）。 */
    val data: String? = null,
    val hint: String? = null,
)

object JwPayloadCodec {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
    }

    private val TIME_REGEX = Regex("^([01]?\\d|2[0-3]):([0-5]\\d)(:([0-5]\\d))?$")

    fun decode(raw: String): JwSchedulePayload {
        val payload = try {
            json.decodeFromString<JwSchedulePayload>(raw)
        } catch (e: SerializationException) {
            val detail = e.message?.lineSequence()?.firstOrNull()?.trim().orEmpty()
            throw JwPackageException("课表载荷不是合法 JSON${if (detail.isEmpty()) "" else "：$detail"}")
        }
        validate(payload)
        return payload
    }

    fun encode(payload: JwSchedulePayload): String = json.encodeToString(payload)

    /** 带定位的校验：错误消息能直接告诉适配器作者是哪一条数据错了。 */
    fun validate(payload: JwSchedulePayload) {
        if (payload.specVersion > JwManifest.SPEC_VERSION) {
            throw JwPackageException("课表载荷规范版本 v${payload.specVersion} 高于本应用支持的 v${JwManifest.SPEC_VERSION}")
        }
        if (payload.warnings.size > JwSchedulePayload.MAX_WARNINGS) {
            throw JwPackageException(
                "核对提示太多（${payload.warnings.size} 条，上限 ${JwSchedulePayload.MAX_WARNINGS} 条）",
            )
        }
        payload.warnings.forEachIndexed { index, warning ->
            val at = "第 ${index + 1} 条核对提示"
            if (warning.isBlank()) throw JwPackageException("$at 是空的")
            if (warning.length > JwSchedulePayload.MAX_WARNING_TEXT) {
                throw JwPackageException("$at 太长（${warning.length} 字，上限 ${JwSchedulePayload.MAX_WARNING_TEXT} 字）")
            }
        }
        when (payload.kind) {
            JwSchedulePayload.KIND_SCHEDULE -> {
                if (payload.terms.isEmpty()) throw JwPackageException("课表载荷里没有任何学期数据")
                payload.terms.forEachIndexed { termIndex, term -> validateTerm(term, termIndex) }
            }
            JwSchedulePayload.KIND_IMAGE -> {
                if (payload.images.isEmpty()) throw JwPackageException("图片载荷里没有任何图片")
                payload.images.forEachIndexed { imageIndex, image ->
                    if (image.url.isNullOrBlank() && image.data.isNullOrBlank()) {
                        throw JwPackageException("第 ${imageIndex + 1} 张图片既没有 url 也没有 data")
                    }
                }
            }
            JwSchedulePayload.KIND_BOXES -> {
                if (payload.boxes.isEmpty()) throw JwPackageException("文本块载荷里没有任何文本块")
                if (payload.boxes.size > JwSchedulePayload.MAX_BOXES) {
                    throw JwPackageException(
                        "文本块太多（${payload.boxes.size} 个，上限 ${JwSchedulePayload.MAX_BOXES} 个）",
                    )
                }
                payload.boxes.forEachIndexed { index, box ->
                    val at = "第 ${index + 1} 个文本块"
                    if (box.text.isBlank()) throw JwPackageException("$at 的 text 是空的")
                    if (box.text.length > JwSchedulePayload.MAX_BOX_TEXT) {
                        throw JwPackageException("$at 的 text 过长（最多 ${JwSchedulePayload.MAX_BOX_TEXT} 字）")
                    }
                    if (box.w < 0 || box.h < 0) throw JwPackageException("$at 的宽高为负（w=${box.w}, h=${box.h}）")
                }
                listOfNotNull(payload.pageWidth, payload.pageHeight).forEach { size ->
                    if (size !in 1..MAX_PAGE_SIZE) {
                        throw JwPackageException("文本块载荷的区域尺寸 $size 超出 1..$MAX_PAGE_SIZE")
                    }
                }
            }
            else -> throw JwPackageException(
                "不认识的载荷类型 kind=\"${payload.kind}\"（支持 schedule / image / boxes）",
            )
        }
    }

    private fun validateTerm(term: JwTerm, termIndex: Int) {
        val at = "第 ${termIndex + 1} 个学期"
        if (term.name.isBlank()) throw JwPackageException("$at 缺少名称 name")
        if (term.totalWeeks !in 1..30) throw JwPackageException("$at 的 totalWeeks=${term.totalWeeks} 超出 1..30")
        if (term.firstDayEpochDay == null && term.firstDay == null) {
            throw JwPackageException("$at 缺少 firstDay（ISO 日期）")
        }
        term.firstDay?.let { day ->
            runCatching { LocalDate.parse(day) }.onFailure {
                throw JwPackageException("$at 的 firstDay「$day」不是 yyyy-MM-dd 格式")
            }
        }
        term.periodTimes.forEachIndexed { index, period ->
            val at2 = "$at 的第 ${index + 1} 个节次"
            if (period.periodIndex < 1) throw JwPackageException("$at2 的 periodIndex 必须 ≥ 1")
            if (!TIME_REGEX.matches(period.start)) throw JwPackageException("$at2 的 start「${period.start}」不是 HH:mm 格式")
            if (!TIME_REGEX.matches(period.end)) throw JwPackageException("$at2 的 end「${period.end}」不是 HH:mm 格式")
            if (toMinutes(period.start) >= toMinutes(period.end)) {
                throw JwPackageException("$at2 的结束时间不晚于开始时间（${period.start} → ${period.end}）")
            }
        }
        term.courses.forEachIndexed { courseIndex, course ->
            val at2 = "$at 的第 ${courseIndex + 1} 门课程"
            if (course.name.isBlank()) throw JwPackageException("$at2 缺少 name")
            course.blocks.forEachIndexed { blockIndex, block ->
                val at3 = "$at2 的第 ${blockIndex + 1} 条安排"
                if (block.dayOfWeek !in 1..7) throw JwPackageException("$at3 的 dayOfWeek=${block.dayOfWeek} 超出 1..7")
                if (block.startPeriod < 1) throw JwPackageException("$at3 的 startPeriod 必须 ≥ 1")
                if (block.endPeriod < block.startPeriod) {
                    throw JwPackageException("$at3 的 endPeriod=${block.endPeriod} 小于 startPeriod=${block.startPeriod}")
                }
                if (block.startWeek < 1) throw JwPackageException("$at3 的 startWeek 必须 ≥ 1")
                if (block.endWeek < block.startWeek) {
                    throw JwPackageException("$at3 的 endWeek=${block.endWeek} 小于 startWeek=${block.startWeek}")
                }
                if (block.endWeek > term.totalWeeks) {
                    throw JwPackageException("$at3 的 endWeek=${block.endWeek} 超出学期总周数 ${term.totalWeeks}")
                }
                if (block.weekType !in WEEK_TYPES) {
                    throw JwPackageException("$at3 的 weekType「${block.weekType}」不合法（ALL / ODD / EVEN）")
                }
            }
        }
    }

    /** "08:00" → 480。 */
    fun toMinutes(text: String): Int {
        val match = TIME_REGEX.matchEntire(text) ?: throw JwPackageException("不是合法时间：$text")
        val hour = match.groupValues[1].toInt()
        val minute = match.groupValues[2].toInt()
        return hour * 60 + minute
    }

    private val WEEK_TYPES = setOf("ALL", "ODD", "EVEN")

    /** 文本块载荷的区域尺寸上限（CSS 像素）。 */
    private const val MAX_PAGE_SIZE = 200_000
}
