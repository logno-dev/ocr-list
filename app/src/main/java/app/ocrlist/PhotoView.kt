package app.ocrlist

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.*
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View

class PhotoView(context: Context) : View(context) {
    private var bitmap: Bitmap? = null
    private var bounds: PhotoBounds? = null
    private val transform = Matrix()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val highlightRect = RectF()
    private val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(65, 210, 128); style = Paint.Style.STROKE; strokeWidth = 5f
    }
    private var scale = 1f
    private var fit = 1f
    private val pinch = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val next = (scale * detector.scaleFactor).coerceIn(fit, fit * 10)
            transform.postScale(next / scale, next / scale, detector.focusX, detector.focusY)
            scale = next; invalidate(); return true
        }
    })
    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true
        override fun onSingleTapUp(e: MotionEvent): Boolean { performClick(); return true }
        override fun onDoubleTap(e: MotionEvent): Boolean { reset(); return true }
        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            transform.postTranslate(-distanceX, -distanceY); invalidate(); return true
        }
    })

    init { contentDescription = "Saved original photo. Pinch to zoom and drag to move."; setBackgroundColor(Color.rgb(25, 29, 26)) }
    fun setPhoto(photo: Bitmap, highlight: PhotoBounds?) { bitmap = photo; bounds = highlight; reset() }
    private fun reset() {
        val image = bitmap ?: return
        if (width == 0 || height == 0) return
        fit = minOf(width.toFloat() / image.width, height.toFloat() / image.height)
        scale = fit
        transform.setScale(fit, fit)
        transform.postTranslate((width - image.width * fit) / 2, (height - image.height * fit) / 2)
        invalidate()
    }
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) = reset()
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val image = bitmap ?: return
        canvas.drawBitmap(image, transform, paint)
        bounds?.let {
            highlightRect.set(it.left * image.width, it.top * image.height, it.right * image.width, it.bottom * image.height)
            transform.mapRect(highlightRect)
            canvas.drawRoundRect(highlightRect, 4f, 4f, highlightPaint)
        }
    }
    @SuppressLint("ClickableViewAccessibility") // GestureDetector calls performClick for single taps.
    override fun onTouchEvent(event: MotionEvent): Boolean {
        parent.requestDisallowInterceptTouchEvent(true)
        pinch.onTouchEvent(event)
        if (!pinch.isInProgress) gestures.onTouchEvent(event)
        return true
    }
    override fun performClick(): Boolean { super.performClick(); return true }
}
