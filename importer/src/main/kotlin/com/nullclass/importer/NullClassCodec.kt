package com.nullclass.importer

import kotlinx.serialization.json.Json

/**
 * 空课课表文档编解码器。
 *
 * TODO(M4):
 *  - [toDomain] / [toDocument]：与 core.model 的双向映射
 *  - WakeUp 课程表（.wakeup_schedule）导入适配
 *  - 二维码编码（文档压缩后分段）
 */
object NullClassCodec {

    private val json = Json {
        prettyPrint = false
        ignoreUnknownKeys = true
    }

    fun encode(document: ScheduleDocument): String = json.encodeToString(document)

    fun decode(raw: String): ScheduleDocument = json.decodeFromString(raw)
}
