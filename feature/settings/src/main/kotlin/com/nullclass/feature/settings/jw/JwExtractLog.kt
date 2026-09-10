package com.nullclass.feature.settings.jw

import android.content.Context
import android.util.Log
import java.io.File

/**
 * 教务提取的 adb 可读日志。
 *
 * - logcat：`adb logcat -s NullClass.Jw`
 * - 文件：`adb shell run-as <包名> cat files/jw-last-error.log`
 *
 * 失败日志会同时进这两处。logcat 单条有长度上限，超长会按块切开。
 */
internal object JwExtractLog {

    const val TAG = "NullClass.Jw"
    const val FILE_NAME = "jw-last-error.log"

    private const val MAX_LINE = 3500

    fun i(message: String) = println(Log.INFO, message)

    fun w(message: String) = println(Log.WARN, message)

    fun e(message: String) = println(Log.ERROR, message)

    fun writeLastError(context: Context, text: String) {
        runCatching {
            File(context.filesDir, FILE_NAME).writeText(text, Charsets.UTF_8)
        }.onFailure { Log.w(TAG, "写 $FILE_NAME 失败", it) }
        e(text)
    }

    fun clearLastError(context: Context) {
        runCatching { File(context.filesDir, FILE_NAME).delete() }
    }

    private fun println(priority: Int, message: String) {
        if (message.length <= MAX_LINE) {
            Log.println(priority, TAG, message)
            return
        }
        val total = (message.length + MAX_LINE - 1) / MAX_LINE
        var index = 0
        var part = 1
        while (index < message.length) {
            val end = minOf(index + MAX_LINE, message.length)
            Log.println(priority, TAG, "($part/$total) " + message.substring(index, end))
            index = end
            part++
        }
    }
}
