package com.nullclass.importer

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.Base64
import java.util.UUID
import java.util.zip.Deflater
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * 课表二维码负载。
 *
 * 二维码 Version 40 字节模式上限 2953B。全量 dump（所有学期 + 墓碑 + 36 位 UUID
 * 原文反复出现）gzip 后再套 base64 几乎必然超限，生成按钮等于瘫痪。
 *
 * 现行 [PREFIX]（v2）：先 [sliceForShare] 只留当前学期活记录，再把 UUID 收成索引表
 * （正文里只写 0/1/2…），JSON 省略 null，gzip 原文直接进码（ISO-8859-1 往返，
 * 不再套 base64）。仍超限时抛 [PayloadTooLargeException]，UI 降级为文件分享。
 *
 * [PREFIX_V1] 旧码（无索引表、gzip 后再 base64 的 ScheduleDocument JSON）仍可解码。
 */
object QrPayload {

    const val PREFIX_V1 = "NULLCLASS1:"
    const val PREFIX_V2 = "NULLCLASS2:"
    const val PREFIX = PREFIX_V2

    /** QR 码可承载的负载上限（Version 40, 字节模式, 纠错 L）。 */
    const val MAX_PAYLOAD_BYTES = 2953

    class PayloadTooLargeException(val size: Int) :
        IllegalArgumentException("课表过大无法生成二维码（$size B > $MAX_PAYLOAD_BYTES B），请用文件分享")

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
        val (ids, interned) = internIds(document)
        val json = compactJson.encodeToString(QrEnvelope(ids = ids, doc = interned))
        val gz = gzip(json.toByteArray(Charsets.UTF_8))
        // 不再包一层 base64：Version 40 上限按「前缀 + 内容」计，base64 会再吞 33%，
        // 16×2 真实 UUID 课表 gzip 后约 2.3KB，加 base64 就略超 2953。
        // 内容是任意字节，用 ISO-8859-1 往返；本应用扫码走 zxing 的 ECI，
        // Intent extra 是带长度的 UTF-16，内嵌 NUL 不会被截断。
        val payload = PREFIX_V2 + String(gz, Charsets.ISO_8859_1)
        if (payload.length > MAX_PAYLOAD_BYTES) throw PayloadTooLargeException(payload.length)
        return payload
    }

    fun decode(payload: String): ScheduleDocument = when {
        payload.startsWith(PREFIX_V2) -> decodeV2(payload.removePrefix(PREFIX_V2))
        payload.startsWith(PREFIX_V1) -> decodeV1(payload.removePrefix(PREFIX_V1))
        else -> throw IllegalArgumentException("不是空课二维码（缺少协议头）")
    }

    /** 供 UI 在弹扫码结果前快速判断是否为空课二维码。 */
    fun isNullClassPayload(text: String): Boolean =
        text.startsWith(PREFIX_V1) || text.startsWith(PREFIX_V2)

    /**
     * 抽出一份「可扫码分享」的活数据：指定学期 + 所属课表 + 该学期的课程/安排/考试/节次。
     * 墓碑和其他学期一律丢掉——二维码不是备份通道。
     */
    fun sliceForShare(document: ScheduleDocument, termId: String): ScheduleDocument {
        val term = document.terms.firstOrNull { it.id == termId && it.deletedAt == null }
            ?: throw IllegalArgumentException("找不到可分享的学期")
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

    private fun decodeV2(body: String): ScheduleDocument {
        val json = try {
            gunzip(body.toByteArray(Charsets.ISO_8859_1))
        } catch (e: java.io.IOException) {
            throw IllegalArgumentException("二维码数据无法解压", e)
        }.toString(Charsets.UTF_8)
        val envelope = try {
            compactJson.decodeFromString(QrEnvelope.serializer(), json)
        } catch (e: Exception) {
            throw IllegalArgumentException("二维码内容损坏", e)
        }
        val table = envelope.ids.map { unpackUuid(it) }
        return applyIds(envelope.doc, table)
    }

    private fun decodeV1(body: String): ScheduleDocument {
        val json = decodeGzipBody(body).toString(Charsets.UTF_8)
        return NullClassCodec.decode(json)
    }

    private fun decodeGzipBody(body: String): ByteArray {
        val gz = try {
            Base64.getUrlDecoder().decode(body)
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException("二维码内容损坏", e)
        }
        return try {
            gunzip(gz)
        } catch (e: java.io.IOException) {
            throw IllegalArgumentException("二维码数据无法解压", e)
        }
    }

    /** 把文档里的 id 收成 0/1/2…，表内存 pack 过的 UUID（或非 UUID 原文）。 */
    private fun internIds(doc: ScheduleDocument): Pair<List<String>, ScheduleDocument> {
        val list = ArrayList<String>()
        val index = HashMap<String, String>()
        fun intern(id: String): String {
            if (id.isEmpty()) return id
            index[id]?.let { return it }
            val token = list.size.toString()
            list.add(packUuid(id))
            index[id] = token
            return token
        }
        val interned = doc.copy(
            deviceId = intern(doc.deviceId),
            timetables = doc.timetables.map { it.copy(id = intern(it.id)) },
            terms = doc.terms.map { it.copy(id = intern(it.id), timetableId = intern(it.timetableId)) },
            courses = doc.courses.map { it.copy(id = intern(it.id), termId = intern(it.termId)) },
            blocks = doc.blocks.map {
                it.copy(id = intern(it.id), courseId = intern(it.courseId), termId = intern(it.termId))
            },
            periodTimes = doc.periodTimes.map { it.copy(termId = intern(it.termId)) },
            exams = doc.exams.map { it.copy(id = intern(it.id), courseId = intern(it.courseId)) },
        )
        return list to interned
    }

    private fun applyIds(doc: ScheduleDocument, table: List<String>): ScheduleDocument {
        fun ext(id: String): String {
            if (id.isEmpty()) return id
            val idx = id.toIntOrNull() ?: return unpackUuid(id)
            return table.getOrElse(idx) { id }
        }
        return doc.copy(
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
    }

    /** 标准 UUID → 16 字节的 base64url（22 字符）；其它 id 原样。 */
    private fun packUuid(id: String): String {
        val uuid = try {
            UUID.fromString(id)
        } catch (_: Exception) {
            return id
        }
        val buf = ByteBuffer.allocate(16)
        buf.putLong(uuid.mostSignificantBits)
        buf.putLong(uuid.leastSignificantBits)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buf.array())
    }

    private fun unpackUuid(id: String): String {
        if (id.length != 22) return id
        val bytes = try {
            Base64.getUrlDecoder().decode(id)
        } catch (_: Exception) {
            return id
        }
        if (bytes.size != 16) return id
        val buf = ByteBuffer.wrap(bytes)
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

@Serializable
private data class QrEnvelope(
    val ids: List<String>,
    val doc: ScheduleDocument,
)
