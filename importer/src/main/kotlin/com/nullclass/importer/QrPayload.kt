package com.nullclass.importer

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * 课表二维码负载：JSON → gzip → base64url，加协议头前缀。
 *
 * 二维码 Version 40 字节模式上限 2953B；负载超限时 [encode] 抛 [PayloadTooLargeException]，
 * UI 降级为文件分享。首版不做 JSON 键缩写（溢出场景才值得，TODO）。
 */
object QrPayload {

    const val PREFIX = "NULLCLASS1:" // 1 = 负载格式版本

    /** QR 码可承载的负载上限（Version 40, 字节模式, 纠错 L）。 */
    const val MAX_PAYLOAD_BYTES = 2953

    class PayloadTooLargeException(val size: Int) :
        IllegalArgumentException("课表过大无法生成二维码（$size B > $MAX_PAYLOAD_BYTES B），请用文件分享")

    fun encode(document: ScheduleDocument): String {
        val json = NullClassCodec.encode(document)
        val gz = gzip(json.toByteArray(Charsets.UTF_8))
        val payload = PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(gz)
        if (payload.length > MAX_PAYLOAD_BYTES) throw PayloadTooLargeException(payload.length)
        return payload
    }

    fun decode(payload: String): ScheduleDocument {
        val body = payload.removePrefix(PREFIX)
        require(body.length != payload.length) { "不是空课二维码（缺少协议头）" }
        val gz = try {
            Base64.getUrlDecoder().decode(body)
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException("二维码内容损坏", e)
        }
        val json = try {
            gunzip(gz)
        } catch (e: java.io.IOException) {
            throw IllegalArgumentException("二维码数据无法解压", e)
        }.toString(Charsets.UTF_8)
        return NullClassCodec.decode(json)
    }

    /** 供 UI 在弹扫码结果前快速判断是否为空课二维码。 */
    fun isNullClassPayload(text: String): Boolean = text.startsWith(PREFIX)

    private fun gzip(bytes: ByteArray): ByteArray =
        ByteArrayOutputStream().use { out ->
            GZIPOutputStream(out).use { it.write(bytes) }
            out.toByteArray()
        }

    private fun gunzip(bytes: ByteArray): ByteArray =
        GZIPInputStream(ByteArrayInputStream(bytes)).use { it.readBytes() }
}
