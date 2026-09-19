package com.nullclass.feature.settings.transfer

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import com.nullclass.importer.QrPayload
import com.nullclass.importer.ScheduleDocument
import com.nullclass.importer.TermDto
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class QrImageReaderTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun withImage(bitmap: Bitmap, test: (Uri) -> Unit) {
        val file = File.createTempFile("qr-reader-", ".png", context.cacheDir)
        try {
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            test(Uri.fromFile(file))
        } finally {
            bitmap.recycle()
            file.delete()
        }
    }

    @Test
    fun binaryQrBytesSurviveGalleryRoundTrip() {
        val payload = "NULLCLASS3:" + (0..255).map(Int::toChar).joinToString("")
        withImage(QrBitmap.encode(payload)) { uri ->
            assertEquals(payload, runBlocking { readQrImage(context, uri) })
        }
    }

    @Test
    fun rotatedScheduleQrCanBeDecodedIntoImportDocument() {
        val document = ScheduleDocument(
            deviceId = "qr-test",
            generatedAt = 1,
            terms = listOf(TermDto("term", "扫码回归学期", 20671, 20, true, createdAt = 1, updatedAt = 1)),
            courses = emptyList(),
            blocks = emptyList(),
            periodTimes = emptyList(),
        )
        val original = QrBitmap.encode(QrPayload.encode(document))
        val rotated = Bitmap.createBitmap(original, 0, 0, original.width, original.height, Matrix().apply { postRotate(90f) }, false)
        original.recycle()
        withImage(rotated) { uri ->
            val restored = QrPayload.decode(runBlocking { readQrImage(context, uri) })
            assertEquals("扫码回归学期", restored.terms.single().name)
        }
    }

    @Test
    fun ordinaryQrReachesPayloadValidation() {
        withImage(QrBitmap.encode("https://example.com")) { uri ->
            assertEquals("https://example.com", runBlocking { readQrImage(context, uri) })
        }
    }

    @Test
    fun imageWithoutQrReportsActionableFailure() {
        val blank = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        withImage(blank) { uri ->
            try {
                runBlocking { readQrImage(context, uri) }
                fail("Expected a no-QR error")
            } catch (e: IllegalArgumentException) {
                assertTrue(e.message.orEmpty().contains("未找到二维码"))
            }
        }
    }
}
