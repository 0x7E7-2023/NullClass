package com.nullclass.importer.jw.ocr

import com.nullclass.importer.jw.JwSchedulePayload

/**
 * 页面文本块（CSS 像素）→ 表格结构层的输入。
 *
 * 与 OCR 共用同一个 [OcrBox] / [OcrPage] / [JwTableAligner]：对结构层来说，
 * 「适配器从 DOM 量出来的文本框」和「OCR 认出来的文本框」没有区别，
 * 所以「星期表头 + 节次列锚定 → 7 列网格」这套逻辑两边一模一样。
 */
object JwBoxes {

    fun toOcrPage(payload: JwSchedulePayload): OcrPage {
        val boxes = payload.boxes.map { box ->
            OcrBox(
                text = box.text.trim(),
                left = box.x,
                top = box.y,
                // 宽高可能缺失（适配器只给了位置）：给 1px 保证 right/bottom 不塌成零
                right = box.x + box.w.coerceAtLeast(1),
                bottom = box.y + box.h.coerceAtLeast(1),
            )
        }
        // 区域尺寸缺省时按文本块外接框推断（对齐层用宽度做「节次列在左侧 1/4」的判断）
        val width = payload.pageWidth ?: boxes.maxOfOrNull { it.right } ?: 1
        val height = payload.pageHeight ?: boxes.maxOfOrNull { it.bottom } ?: 1
        return OcrPage(width.coerceAtLeast(1), height.coerceAtLeast(1), boxes)
    }
}
