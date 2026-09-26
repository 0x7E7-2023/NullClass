package com.nullclass.core.data.prefs

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max

/**
 * 课表背景图片（「我的 → 个性化设置」）。
 *
 * 用户选的图先按屏幕降采样、旋正，再以 JPEG 存进 `filesDir/wallpaper/`；偏好里只记文件名。
 * 原图可能是几千万像素的相机照片，不降采样直接解码会吃掉上百 MB。
 *
 * **目录里只留当前指针指向的那一个文件**，这是一条不变式而不是某一步「删旧文件」：
 * 切指针之后、删旧文件之前进程被杀，会留下没人引用的旧图；每次导入/清除后以及首次加载时
 * 整个目录扫一遍，下一次就会被扫掉。扫目录与导入都在 [mutex] 里，扫的时候不会误删
 * 「新文件已写好、指针还没切过去」的那一张。
 *
 * 指针指向的文件不存在或解不开（系统备份只恢复了偏好、没恢复图片；文件坏了）时按「没有壁纸」处理，
 * 并顺手清掉这个坏指针。
 */
@Singleton
class ScheduleWallpaperStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val preferences: UserPreferencesRepository,
) {

    /**
     * 本模块第一个自持作用域的单例：解好的图要在课表页与个性化设置页之间共用，
     * 切 Tab 不重复解码、不闪一下，生命期就是进程。导入、清除、解码都在 IO 线程。
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val mutex = Mutex()

    private val directory: File get() = File(context.filesDir, DIRECTORY)

    /** 当前壁纸；null = 没有壁纸（或文件已丢失）。 */
    val wallpaper: StateFlow<Bitmap?> = preferences.scheduleWallpaperFile
        .map { name -> name?.let { load(it) } }
        .stateIn(scope, SharingStarted.Eagerly, null)

    init {
        scope.launch { sweep() }
    }

    /**
     * 把 [uri] 指向的图片设为壁纸。失败（读不到、不是图片、平台解不了的格式 ——
     * 例如 Android 8.x 上的 HEIC）返回 false，原壁纸不受影响。
     *
     * 在本单例的作用域里跑，不随调用方取消：选完图立刻返回上一页，图照样设上；
     * 也不会停在「文件写好、指针没切」的半截，留下一张没人引用的图。
     */
    suspend fun import(uri: Uri): Boolean = scope.async { importNow(uri) }.await()

    /** 移除壁纸。同 [import]，不随调用方取消。 */
    suspend fun clear() {
        scope.async {
            mutex.withLock {
                preferences.setScheduleWallpaperFile(null)
                sweepLocked()
            }
        }.await()
    }

    private suspend fun importNow(uri: Uri): Boolean {
        val bitmap = decodeScaled(uri) ?: return false
        mutex.withLock {
            directory.mkdirs()
            val name = "$FILE_PREFIX${System.currentTimeMillis()}.jpg"
            val target = File(directory, name)
            // 先写临时文件再改名：写到一半被杀也不会留下一张半截的壁纸
            val temp = File(directory, "$name.tmp")
            val written = try {
                temp.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) } &&
                    temp.renameTo(target)
            } catch (_: Exception) {
                false
            }
            if (!written) {
                temp.delete()
                return false
            }
            preferences.setScheduleWallpaperFile(name)
            sweepLocked()
        }
        return true
    }

    private suspend fun load(name: String): Bitmap? = withContext(Dispatchers.IO) {
        val file = File(directory, name)
        val bitmap = try {
            if (file.isFile) BitmapFactory.decodeFile(file.path) else null
        } catch (_: OutOfMemoryError) {
            // 一时内存不够不等于文件坏了：这次不显示，指针留着，下次还能读
            return@withContext null
        }
        // 文件丢了或解不开：按没有壁纸处理，清掉坏指针
        if (bitmap == null) preferences.clearScheduleWallpaperFileIf(name)
        bitmap
    }

    private suspend fun sweep() = mutex.withLock { sweepLocked() }

    /** 删掉目录里除当前指针以外的所有文件（含写到一半的临时文件）。调用方须持有 [mutex]。 */
    private suspend fun sweepLocked() {
        val keep = preferences.scheduleWallpaperFile.first()
        directory.listFiles()?.forEach { file ->
            if (file.name != keep) file.delete()
        }
    }

    /**
     * 解码并缩到长边不超过 min(屏幕长边, [MAX_EDGE_PX])，按 EXIF 方向旋正。
     * 先只读尺寸定 inSampleSize（2 的幂，粗缩），再精确缩放 —— 粗缩那一步决定了内存峰值。
     */
    private fun decodeScaled(uri: Uri): Bitmap? = try {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            null
        } else {
            val metrics = context.resources.displayMetrics
            val targetEdge = max(metrics.widthPixels, metrics.heightPixels).coerceIn(1, MAX_EDGE_PX)
            val sourceEdge = max(bounds.outWidth, bounds.outHeight)
            var sample = 1
            while (sourceEdge / (sample * 2) >= targetEdge) sample *= 2
            val options = BitmapFactory.Options().apply { inSampleSize = sample }
            val decoded = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
            decoded?.let { transform(it, targetEdge, readOrientation(uri)) }
        }
    } catch (_: Exception) {
        null
    } catch (_: OutOfMemoryError) {
        null
    }

    private fun readOrientation(uri: Uri): Int = try {
        context.contentResolver.openInputStream(uri)?.use {
            ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        } ?: ExifInterface.ORIENTATION_NORMAL
    } catch (_: Exception) {
        ExifInterface.ORIENTATION_NORMAL
    }

    /** 缩到长边 [targetEdge] 以内并按 EXIF [orientation] 旋正 / 翻转，一次 createBitmap 完成。 */
    private fun transform(source: Bitmap, targetEdge: Int, orientation: Int): Bitmap {
        val matrix = Matrix()
        val edge = max(source.width, source.height)
        if (edge > targetEdge) {
            val scale = targetEdge.toFloat() / edge
            matrix.postScale(scale, scale)
        }
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.postRotate(90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.postRotate(-90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(-90f)
        }
        if (matrix.isIdentity) return source
        val result = Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
        if (result !== source) source.recycle()
        return result
    }

    private companion object {
        const val DIRECTORY = "wallpaper"
        const val FILE_PREFIX = "schedule-"
        const val JPEG_QUALITY = 90

        /** 长边上限：再大肉眼看不出差别，只多占内存（2560 × 1440 的 ARGB 约 14MB）。 */
        const val MAX_EDGE_PX = 2560
    }
}
