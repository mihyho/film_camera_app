package com.example.filmcamera

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.ExifInterface
import android.provider.MediaStore
import com.example.filmcamera.data.Mode
import java.io.File
import java.util.stream.IntStream

/** 저장 결과. 필름 모드는 file(앱 전용 저장소 기준 상대 경로), 단일 촬영은 uri(갤러리 주소) */
data class SavedPhoto(val file: String?, val uri: String?)

/** JPEG 바이트 -> LUT 적용 -> 모드에 맞는 곳에 저장. 무거우므로 반드시 백그라운드 스레드에서 호출. */
object PhotoPipeline {

    private const val STRIP_ROWS = 256
    private const val JPEG_QUALITY = 95

    fun process(context: Context, jpeg: ByteArray, rotationDegrees: Int, lut: CubeLut, mode: Mode): SavedPhoto {
        val opts = BitmapFactory.Options().apply {
            inMutable = true
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val bmp = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, opts)
            ?: throw IllegalStateException("촬영본 디코딩 실패")

        // 필름 모드 촬영본에만 그레인을 얹는다(단일 촬영은 LUT만)
        applyLut(bmp, lut, grain = mode == Mode.FILM)

        val tmp = File.createTempFile("shot_", ".jpg", context.cacheDir)
        try {
            tmp.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
            bmp.recycle()
            // 픽셀은 센서 방향 그대로이므로 EXIF 회전 태그로 똑바로 보이게 한다
            ExifInterface(tmp.absolutePath).apply {
                setAttribute(ExifInterface.TAG_ORIENTATION, exifOrientation(rotationDegrees).toString())
                saveAttributes()
            }
            val name = "FILM_" + java.text.SimpleDateFormat("yyyyMMdd_HHmmss_SSS", java.util.Locale.US)
                .format(java.util.Date()) + ".jpg"
            return when (mode) {
                Mode.SINGLE -> SavedPhoto(null, saveToGallery(context, tmp, name).toString())
                Mode.FILM -> SavedPhoto(saveToPrivate(context, tmp, name), null)
            }
        } finally {
            tmp.delete()
        }
    }

    /** LUT(+필름 모드면 그레인). 큰 이미지를 통째로 IntArray로 만들지 않도록 띠(strip) 단위로 처리하고, 띠 안에서는 병렬 처리 */
    private fun applyLut(bmp: Bitmap, lut: CubeLut, grain: Boolean) {
        val w = bmp.width
        val h = bmp.height
        val strip = IntArray(w * STRIP_ROWS)
        var y = 0
        while (y < h) {
            val rows = minOf(STRIP_ROWS, h - y)
            bmp.getPixels(strip, 0, w, 0, y, w, rows)
            val total = w * rows
            val chunk = (total + 3) / 4
            IntStream.range(0, 4).parallel().forEach { c ->
                val from = c * chunk
                val to = minOf(total, from + chunk)
                if (from < to) {
                    LutApplier.applyArgb(strip, from, to, lut)
                    if (grain) Grain.applyArgb(strip, from, to, w, y)
                }
            }
            bmp.setPixels(strip, 0, w, 0, y, w, rows)
            y += rows
        }
    }

    private fun exifOrientation(deg: Int) = when ((deg % 360 + 360) % 360) {
        90 -> ExifInterface.ORIENTATION_ROTATE_90
        180 -> ExifInterface.ORIENTATION_ROTATE_180
        270 -> ExifInterface.ORIENTATION_ROTATE_270
        else -> ExifInterface.ORIENTATION_NORMAL
    }

    private fun saveToGallery(context: Context, file: File, name: String): android.net.Uri {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/FilmCamera")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("갤러리 항목 생성 실패")
        try {
            resolver.openOutputStream(uri)!!.use { out -> file.inputStream().use { it.copyTo(out) } }
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            return uri
        } catch (e: Exception) {
            resolver.delete(uri, null, null) // 반쪽짜리 항목을 남기지 않는다
            throw e
        }
    }

    /** 필름 모드: 앱 전용 폴더(갤러리에 안 보임). 4단계에서 롤 단위 관리/현상으로 확장한다. */
    private fun saveToPrivate(context: Context, file: File, name: String): String {
        val dir = File(context.filesDir, "rolls/pending").apply { mkdirs() }
        val dst = File(dir, name)
        file.copyTo(dst, overwrite = false)
        return "rolls/pending/$name"
    }
}
