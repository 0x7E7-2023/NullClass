package com.nullclass.importer

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.UUID
import java.util.zip.Deflater
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * 课表二维码负载：JSON → gzip → base64url，加协议头前缀。
 *
 * 二维码 Version 40 字节模式上限 2953B，模块数随负载增长——负载每砍 100B 左右
 * 就能小一个版本、模块大一圈，越好扫。编码侧已在格式上限附近（字节模式、
 * 纠错 L、gzip BEST_COMPRESSION；base45 塞字母数字模式反而更差），能砍的只有内容：
 *
 * - [sliceForShare] 只留当前学期活记录（不带其他学期与墓碑）；
 * - **UUID 身份表整个不进码**（[PREFIX_V3]，现行格式）：重铸 ID 的分享包，
 *   所有 id 在码内只是 0/1/2… 索引，接收端解出时重新生成 UUID。这张表每条 16B
 *   且不可压，占负载四成——砍掉后典型学期（16 课 × 32 安排）从 version 36
 *   降到 27、模块大 28%。合并语义与 WakeUp 迁移一致（ImportProvenance.QR_IMPORT，
 *   同名学期按内容对齐，重扫新版时删除也传播）。
 *
 * 旧码仍可解：[PREFIX_V1]（gzip+base64 的全量文档）、[PREFIX_V2]（gzip 直进码、
 * 带 UUID 索引表的当前学期包）。
 */
object QrPayload {

    const val PREFIX_V1 = "NULLCLASS1:"
    const val PREFIX_V2 = "NULLCLASS2:"
    const val PREFIX_V3 = "NULLCLASS3:"
    const val PREFIX = PREFIX_V3

    /** QR 码可承载的负载上限（Version 40, 字节模式, 纠错 L）。 */
    const val MAX_PAYLOAD_BYTES = 2953

    /** 负载超过二维码容量上限。只带尺寸，文案由界面层按当前语言取。 */
    class PayloadTooLargeException(val size: Int, val limit: Int = MAX_PAYLOAD_BYTES) :
        IllegalArgumentException("PayloadTooLarge($size > $limit)")

    /**
     * 二维码专用：省略 null 字段（teacher/note/deletedAt/location…），但保留
     * formatVersion 等有默认值的非 null 字段，避免旧客户端把缺字段读成别的版本。
     */
    private val compactJson = Json {
        prettyPrint = false
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    fun encode(document: ScheduleDocument): String {
        // 分享包不带身份：deviceId 换成空（接收端盖上 QR_IMPORT 来源章），其余 id 全部
        // 收成 0/1/2… 索引。表不进码，解码时按索引现铸新 UUID。
        val interned = internIds(document.copy(deviceId = ""))
        val json = compactJson.encodeToString(ScheduleDocument.serializer(), interned)
        val gz = gzip(json.toByteArray(Charsets.UTF_8))
        // 不再包一层 base64：Version 40 上限按「前缀 + 内容」计，base64 会再吞 33%。
        // 内容是任意字节，用 ISO-8859-1 往返；扫码走 zxing 的字节模式，
        // Intent extra 是带长度的 UTF-16，内嵌 NUL 不会被截断。
        val payload = PREFIX_V3 + String(gz, Charsets.ISO_8859_1)
        if (payload.length > MAX_PAYLOAD_BYTES) throw PayloadTooLargeException(payload.length)
        return payload
    }

    fun decode(payload: String): ScheduleDocument = when {
        payload.startsWith(PREFIX_V3) -> decodeV3(payload.removePrefix(PREFIX_V3))
        payload.startsWith(PREFIX_V2) -> decodeV2(payload.removePrefix(PREFIX_V2))
        payload.startsWith(PREFIX_V1) -> decodeV1(payload.removePrefix(PREFIX_V1))
        else -> throw ScheduleFileException(ScheduleFileError.QR_NOT_NULLCLASS)
    }

    /** 供 UI 在弹扫码结果前快速判断是否为空课二维码。 */
    fun isNullClassPayload(text: String): Boolean =
        text.startsWith(PREFIX_V1) || text.startsWith(PREFIX_V2) || text.startsWith(PREFIX_V3)

    /**
     * 抽出一份「可扫码分享」的活数据：指定学期 + 所属课表 + 该学期的课程/安排/考试/节次。
     * 墓碑和其他学期一律丢掉——二维码不是备份通道。
     */
    fun sliceForShare(document: ScheduleDocument, termId: String): ScheduleDocument {
        val term = document.terms.firstOrNull { it.id == termId && it.deletedAt == null }
            ?: throw ScheduleFileException(ScheduleFileError.QR_NO_SHAREABLE_TERM)
        val timetable = term.timetableId.takeIf { it.isNotEmpty() }?.let { id ->
            document.timetables.firstOrNull { it.id == id && it.deletedAt == null }
        }
        val courses = document.courses.filter { it.deletedAt == null && it.termId == term.id }
        val courseIds = courses.map { it.id }.toSet()
        return document.copy(
            timetables = listOfNotNull(timetable),
            terms = listOf(term),
            courses = courses,
            blocks = document.blocks.filter {
                it.deletedAt == null && it.termId == term.id && it.courseId in courseIds
            },
            periodTimes = document.periodTimes.filter { it.termId == term.id },
            exams = document.exams.filter { it.deletedAt == null && it.courseId in courseIds },
        )
    }

    /** v3 解码：按索引现铸 UUID，盖上扫码分享来源章（对齐合并见 ImportProvenance）。 */
    private fun decodeV3(body: String): ScheduleDocument {
        val document = decodeGzipJson(body)
        val fresh = HashMap<Int, String>()
        fun ext(id: String): String {
            val idx = id.toIntOrNull() ?: return id
            return fresh.getOrPut(idx) { UUID.randomUUID().toString() }
        }
        return applyIds(document, ::ext).copy(deviceId = ImportProvenance.QR_IMPORT)
    }

    /** v2 解码：索引表里是 pack 过的 UUID，按表还原。 */
    private fun decodeV2(body: String): ScheduleDocument {
        val json = decodeGzipBody(body).toString(Charsets.UTF_8)
        val envelope = try {
            compactJson.decodeFromString(QrEnvelope.serializer(), json)
        } catch (e: Exception) {
            throw ScheduleFileException(ScheduleFileError.QR_CORRUPT)
        }
        val table = envelope.ids.map { unpackUuid(it) }
        return applyIds(envelope.doc) { id ->
            if (id.isEmpty()) id else table.getOrElse(id.toIntOrNull() ?: -1) { unpackUuid(id) }
        }
    }

    private fun decodeV1(body: String): ScheduleDocument {
        val gz = try {
            Base64.getUrlDecoder().decode(body)
        } catch (e: IllegalArgumentException) {
            throw ScheduleFileException(ScheduleFileError.QR_CORRUPT)
        }
        val json = try {
            gunzip(gz)
        } catch (e: java.io.IOException) {
            throw ScheduleFileException(ScheduleFileError.QR_GUNZIP_FAILED)
        }.toString(Charsets.UTF_8)
        return NullClassCodec.decode(json)
    }

    private fun decodeGzipJson(body: String): ScheduleDocument {
        val json = decodeGzipBody(body).toString(Charsets.UTF_8)
        return try {
            compactJson.decodeFromString(ScheduleDocument.serializer(), json)
        } catch (e: Exception) {
            throw ScheduleFileException(ScheduleFileError.QR_CORRUPT)
        }
    }

    private fun decodeGzipBody(body: String): ByteArray {
        val raw = body.toByteArray(Charsets.ISO_8859_1)
        return try {
            gunzip(raw)
        } catch (e: java.io.IOException) {
            throw ScheduleFileException(ScheduleFileError.QR_GUNZIP_FAILED)
        }
    }

    /** 把文档里的 id 全部收成首次出现顺序的 0/1/2… 索引。 */
    private fun internIds(doc: ScheduleDocument): ScheduleDocument {
        val index = HashMap<String, String>()
        fun intern(id: String): String {
            if (id.isEmpty()) return id
            return index.getOrPut(id) { index.size.toString() }
        }
        return doc.copy(
            timetables = doc.timetables.map { it.copy(id = intern(it.id)) },
            terms = doc.terms.map { it.copy(id = intern(it.id), timetableId = intern(it.timetableId)) },
            courses = doc.courses.map { it.copy(id = intern(it.id), termId = intern(it.termId)) },
            blocks = doc.blocks.map {
                it.copy(id = intern(it.id), courseId = intern(it.courseId), termId = intern(it.termId))
            },
            periodTimes = doc.periodTimes.map { it.copy(termId = intern(it.termId)) },
            exams = doc.exams.map { it.copy(id = intern(it.id), courseId = intern(it.courseId)) },
        )
    }

    /** [ext] 把码内索引还原成真 id（v2 查表，v3 现铸）。 */
    private fun applyIds(doc: ScheduleDocument, ext: (String) -> String): ScheduleDocument =
        doc.copy(
            deviceId = ext(doc.deviceId),
            timetables = doc.timetables.map { it.copy(id = ext(it.id)) },
            terms = doc.terms.map { it.copy(id = ext(it.id), timetableId = ext(it.timetableId)) },
            courses = doc.courses.map { it.copy(id = ext(it.id), termId = ext(it.termId)) },
            blocks = doc.blocks.map {
                it.copy(id = ext(it.id), courseId = ext(it.courseId), termId = ext(it.termId))
            },
            periodTimes = doc.periodTimes.map { it.copy(termId = ext(it.termId)) },
            exams = doc.exams.map { it.copy(id = ext(it.id), courseId = ext(it.courseId)) },
        )

    /** 标准 UUID 的 22 字符 base64url 还原；其它 id 原样。v2 解码用。 */
    private fun unpackUuid(id: String): String {
        if (id.length != 22) return id
        val bytes = try {
            Base64.getUrlDecoder().decode(id)
        } catch (_: Exception) {
            return id
        }
        if (bytes.size != 16) return id
        val buf = java.nio.ByteBuffer.wrap(bytes)
        return UUID(buf.long, buf.long).toString()
    }

    private fun gzip(bytes: ByteArray): ByteArray =
        ByteArrayOutputStream().use { out ->
            object : GZIPOutputStream(out) {
                init {
                    def.setLevel(Deflater.BEST_COMPRESSION)
                }
            }.use { it.write(bytes) }
            out.toByteArray()
        }

    private fun gunzip(bytes: ByteArray): ByteArray =
        GZIPInputStream(ByteArrayInputStream(bytes)).use { it.readBytes() }
}

/** v2 码的信封：索引表 + 正文。 */
@kotlinx.serialization.Serializable
private data class QrEnvelope(
    val ids: List<String>,
    val doc: ScheduleDocument,
)
