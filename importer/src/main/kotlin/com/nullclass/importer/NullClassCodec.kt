package com.nullclass.importer

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * 空课课表文档编解码器（.nullclass 文件 / WebDAV snapshot.json 共用）。
 */
object NullClassCodec {

    /**
     * formatVersion 超过本应用支持的版本。
     *
     * 只带版本号，不带文案：文案由界面层按当前语言取（词条里会代入这两个版本）。
     */
    class FutureVersionException(val fileVersion: Int, val supportedVersion: Int) :
        IllegalArgumentException("FutureVersion($fileVersion > $supportedVersion)")

    private val json = Json {
        prettyPrint = false
        ignoreUnknownKeys = true
        // 新增默认字段显式写出：新旧版本写出的快照字节级一致，跨版本行为可预测
        encodeDefaults = true
    }

    fun encode(document: ScheduleDocument): String = json.encodeToString(document)

    fun decode(raw: String): ScheduleDocument {
        val document = try {
            json.decodeFromString<ScheduleDocument>(raw)
        } catch (e: SerializationException) {
            throw ScheduleFileException(ScheduleFileError.NULLCLASS_INVALID_FILE)
        }
        if (document.formatVersion > ScheduleDocument.FORMAT_VERSION) {
            throw FutureVersionException(document.formatVersion, ScheduleDocument.FORMAT_VERSION)
        }
        return document
    }
}
