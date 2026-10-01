package app.ocrlist

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt

object PhotoLoader {
    // ImageDecoder applies EXIF orientation, so OCR bounds and displayed crops agree.
    fun decode(file: File, maxEdge: Int = 2400): Bitmap =
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, info, _ ->
            val ratio = minOf(1f, maxEdge.toFloat() / max(info.size.width, info.size.height))
            decoder.setTargetSize(max(1, (info.size.width * ratio).roundToInt()), max(1, (info.size.height * ratio).roundToInt()))
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
}
